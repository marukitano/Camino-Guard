package com.marukitano.caminoguard;

import android.app.Activity;
import android.graphics.PointF;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns the projected Camino height-profile overlay and its refresh lifecycle.
 *
 * The profile is deliberately independent from Camino selection/measurement.
 *
 * For each horizontal screen row, all visible Camino points compete with each
 * other. Only the point furthest to the physical right survives. This creates
 * one right-edge elevation envelope across every visible Camino without ever
 * choosing a single "active route".
 */
final class CaminoHeightProfileController {

    private static final long REFRESH_DELAY_MS =
            CaminoConfig.get().longValue(
                    "measurement.heightProfileRefreshDelayMs"
            );

    /*
     * One candidate per two physical screen pixels gives almost full visual
     * resolution while avoiding needless work above the View's 1000-sample
     * drawing limit on tall phones.
     */
    private static final float ROW_BUCKET_PX =
            2.0f;

    /*
     * The profile is only useful once the map is at roughly the scale where
     * Malaga and Baena can still be visible together. Their straight-line
     * separation is about 100 km; 105 km gives a little edge margin.
     */
    private static final double PROFILE_SHOW_VIEWPORT_HEIGHT_M =
            50_000.0;

    private static final double PROFILE_HIDE_VIEWPORT_HEIGHT_M =
            80_000.0;

    private static final double SLOPE_WINDOW_M =
            100.0;

    /*
     * A physical GPS position is only a position ON the selected route when it
     * is plausibly close to that route. Never snap a Swiss position onto a
     * Camino hundreds or thousands of kilometres away.
     */
    private static final double LOCKED_POSITION_MAX_OFFSET_M =
            CaminoConfig.get().doubleValue(
                    "navigation.offRouteThresholdMeters"
            );

    private final Activity activity;
    private final MapView mapView;
    private final CaminoInfoPresenter infoPresenter;
    private final List<CaminoRoute> routes;
    private final CaminoSettlementTimetableSource settlementSource;

    private MapLibreMap map;
    private CaminoHeightProfileView view;
    private boolean refreshScheduled;

    private boolean profileScaleInitialized;
    private boolean profileScaleAvailable;

    /*
     * When an explicit route is marked, the profile switches from the
     * viewport-wide Camino envelope to exactly this measured route.
     */
    private MeasurementPath selectedMeasurementPath;

    /*
     * Static selected-route drawing data. GPS updates only move the locked
     * position marker; they do not change route elevations, slopes or villages.
     */
    private MeasurementPath cachedSelectedProfilePath;

    private List<CaminoHeightProfileView.Sample>
            cachedSelectedSamples =
            Collections.emptyList();

    private List<CaminoHeightProfileView.Sample>
            cachedSelectedVillageSamples =
            Collections.emptyList();

    private MeasurementPath appliedSelectedProfilePath;

    /*
     * While a marked route is locked, show the current live/dummy position
     * permanently on that route's elevation profile.
     */
    private LatLng lockedSelectionPosition;
    private boolean lockedSelectionActive;

    private final Runnable refreshRunnable =
            () -> {
                refreshScheduled =
                        false;

                refreshVisibleEnvelope();
            };

    CaminoHeightProfileController(
            Activity activity,
            MapView mapView,
            CaminoInfoPresenter infoPresenter,
            List<CaminoRoute> routes
    ) {
        this.activity =
                activity;

        this.mapView =
                mapView;

        this.infoPresenter =
                infoPresenter;

        this.routes =
                routes;

        this.settlementSource =
                new CaminoSettlementTimetableSource(
                        activity
                );
    }

    void attachMap(
            MapLibreMap map
    ) {
        this.map =
                map;
    }

    void ensureView() {
        if (view
                != null) {
            return;
        }

        view =
                new CaminoHeightProfileView(
                        activity
                );

        view.setLockedProfileStyle(
                lockedSelectionActive
        );

        /*
         * The transparent full-screen View must exist even while the profile is
         * closed, because the right-edge chevron itself is the on/off control.
         */
        view.setVisibility(
                android.view.View.VISIBLE
        );

        view.setProfileVisibilityListener(
                visible -> {
                    if (visible) {
                        if (updateProfileAvailability()) {
                            refreshVisibleEnvelope();
                        }

                    } else {
                        infoPresenter.setHeightStats(
                                ""
                        );
                    }
                }
        );

        /*
         * Width/height may still be zero while ensureView() runs. Re-check once
         * the MapView has completed layout so the chevron starts in the correct
         * visible/hidden state.
         */
        mapView.post(
                this::updateProfileAvailability
        );

        ViewGroup parent =
                (ViewGroup)
                        mapView.getParent();

        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.TOP
                                | Gravity.START
                );

        parent.addView(
                view,
                params
        );

        view.setElevation(
                dp(
                        2
                )
        );
    }

    void setMeasurementPath(
            MeasurementPath path
    ) {
        boolean previouslyMarked =
                hasUsableMeasurementPath(
                        selectedMeasurementPath
                );

        selectedMeasurementPath =
                hasUsableMeasurementPath(
                        path
                )
                        ? path
                        : null;

        if (selectedMeasurementPath
                != cachedSelectedProfilePath) {

            cachedSelectedProfilePath =
                    null;

            cachedSelectedSamples =
                    Collections.emptyList();

            cachedSelectedVillageSamples =
                    Collections.emptyList();

            appliedSelectedProfilePath =
                    null;
        }

        boolean nowMarked =
                hasUsableMeasurementPath(
                        selectedMeasurementPath
                );

        /*
         * A newly marked route should reveal its elevation profile
         * automatically. This is intentionally edge-triggered:
         *
         *   unmarked -> marked : open once
         *   marked   -> marked : keep the user's current open/closed choice
         *
         * That means camera/GPS refreshes never fight a user who manually
         * closes the profile after selecting a route.
         */
        if (!previouslyMarked
                && nowMarked) {

            autoOpenMarkedMeasurementProfile();
        }
    }

    private void autoOpenMarkedMeasurementProfile() {
        if (view == null) {
            return;
        }

        if (!updateProfileAvailability()) {
            return;
        }

        view.showProfile();
    }

    /*
     * Last trustworthy projection on the locked MeasurementPath.
     *
     * When real GPS leaves the configured 20 m route corridor, the visible
     * profile marker stays here until a valid on-route projection exists again.
     */
    private CaminoHeightProfileView.Sample
            lastLockedPositionSample;


    void setLockedSelectionPosition(
            LatLng position,
            boolean locked
    ) {
        boolean wasLocked =
                lockedSelectionActive;

        if (!locked) {
            lastLockedPositionSample =
                    null;
        }

        lockedSelectionPosition =
                locked
                        ? position
                        : null;

        lockedSelectionActive =
                locked
                        && position != null;

        if (view
                != null) {

            view.setLockedProfileStyle(
                    lockedSelectionActive
            );

            /*
             * Locking is an explicit mode change. If the profile was manually
             * closed after marking the route, reveal it again when the lock is
             * engaged so position + permanent elevation are actually visible.
             */
            if (!wasLocked
                    && lockedSelectionActive) {

                view.showProfile();
            }
        }
    }


    private boolean hasUsableMeasurementPath(
            MeasurementPath path
    ) {
        return path != null
                && path.profilePoints != null
                && path.profilePoints.size() >= 2
                && Double.isFinite(
                path.distanceM
        )
                && path.distanceM > 0.0;
    }


    void scheduleRefresh() {
        if (view
                == null) {
            return;
        }

        if (!updateProfileAvailability()) {
            return;
        }

        if (view.isProfileHidden()
                || refreshScheduled) {
            return;
        }

        refreshScheduled =
                true;

        mapView.postDelayed(
                refreshRunnable,
                REFRESH_DELAY_MS
        );
    }

    void handleCameraIdle() {
        mapView.removeCallbacks(
                refreshRunnable
        );

        refreshScheduled =
                false;

        if (view
                == null) {
            return;
        }

        if (!updateProfileAvailability()) {
            return;
        }

        if (view.isProfileHidden()) {
            return;
        }

        refreshVisibleEnvelope();
    }

    void refresh() {
        if (view
                == null) {
            return;
        }

        if (!updateProfileAvailability()) {
            return;
        }

        if (view.isProfileHidden()) {
            return;
        }

        refreshVisibleEnvelope();
    }

    private boolean updateProfileAvailability() {
        /*
         * A marked measurement has its own finite scale and therefore does not
         * depend on how far the map is zoomed out. The old 50/80-km hysteresis
         * remains unchanged whenever no measurement is selected.
         */
        if (view != null
                && hasUsableMeasurementPath(
                selectedMeasurementPath
        )) {

            profileScaleAvailable =
                    true;

            profileScaleInitialized =
                    true;

            view.setProfileAvailable(
                    true
            );

            return true;
        }

        if (view
                == null
                || map
                == null
                || mapView.getWidth()
                <= 0
                || mapView.getHeight()
                <= 0) {

            if (view
                    != null) {

                view.setProfileAvailable(
                        false
                );
            }

            profileScaleAvailable =
                    false;

            profileScaleInitialized =
                    true;

            return false;
        }

        org.maplibre.android.geometry.VisibleRegion visibleRegion =
                map.getProjection()
                        .getVisibleRegion();

        double leftHeightM =
                GeoMath.distanceMeters(
                        visibleRegion.farLeft,
                        visibleRegion.nearLeft
                );

        double rightHeightM =
                GeoMath.distanceMeters(
                        visibleRegion.farRight,
                        visibleRegion.nearRight
                );

        double viewportHeightM =
                Math.max(
                        leftHeightM,
                        rightHeightM
                );

        if (!Double.isFinite(
                viewportHeightM
        )) {

            view.setProfileAvailable(
                    false
            );

            profileScaleAvailable =
                    false;

            profileScaleInitialized =
                    true;

            return false;
        }

        /*
         * Real hysteresis:
         *
         *   <= 50 km  -> available
         *   50..80 km -> keep previous state
         *   > 80 km   -> unavailable
         *
         * On first evaluation there is no previous state, so the profile only
         * becomes available if we are already inside the 50 km threshold.
         */
        if (!profileScaleInitialized) {
            profileScaleAvailable =
                    viewportHeightM
                            <= PROFILE_SHOW_VIEWPORT_HEIGHT_M;

            profileScaleInitialized =
                    true;

        } else if (profileScaleAvailable) {

            if (viewportHeightM
                    > PROFILE_HIDE_VIEWPORT_HEIGHT_M) {

                profileScaleAvailable =
                        false;
            }

        } else if (viewportHeightM
                <= PROFILE_SHOW_VIEWPORT_HEIGHT_M) {

            profileScaleAvailable =
                    true;
        }

        view.setProfileAvailable(
                profileScaleAvailable
        );

        if (!profileScaleAvailable) {
            infoPresenter.setHeightStats(
                    ""
            );
        }

        return profileScaleAvailable;
    }


    private void refreshVisibleEnvelope() {
        if (view
                == null
                || map
                == null
                || view.isProfileHidden()
                || routes.isEmpty()
                || mapView.getWidth()
                <= 0
                || mapView.getHeight()
                <= 0) {

            return;
        }

        float width =
                mapView.getWidth();

        float height =
                mapView.getHeight();

        if (hasUsableMeasurementPath(
                selectedMeasurementPath
        )) {

            view.setMarkedSelectionProfile(
                    true
            );

            if (cachedSelectedProfilePath
                    != selectedMeasurementPath) {

                List<CaminoHeightProfileView.Sample> builtSamples =
                        buildSelectedMeasurementSamples(
                                selectedMeasurementPath
                        );

                MeasurementPath settlementPath =
                        settlementSource.withSettlementStops(
                                selectedMeasurementPath
                        );

                List<CaminoHeightProfileView.Sample> builtVillageSamples =
                        buildVillageSamples(
                                selectedMeasurementPath,
                                settlementPath
                        );

                cachedSelectedSamples =
                        Collections.unmodifiableList(
                                new ArrayList<>(
                                        builtSamples
                                )
                        );

                cachedSelectedVillageSamples =
                        Collections.unmodifiableList(
                                new ArrayList<>(
                                        builtVillageSamples
                                )
                        );

                cachedSelectedProfilePath =
                        selectedMeasurementPath;

                appliedSelectedProfilePath =
                        null;
            }

            if (cachedSelectedSamples.size() < 2) {
                view.clearProfile();

                infoPresenter.setHeightStats(
                        ""
                );

                return;
            }

            if (appliedSelectedProfilePath
                    != selectedMeasurementPath) {

                view.setSamples(
                        cachedSelectedSamples
                );

                view.setVillageSamples(
                        cachedSelectedVillageSamples
                );

                appliedSelectedProfilePath =
                        selectedMeasurementPath;
            }

            CaminoHeightProfileView.Sample
                    currentLockedPositionSample =
                    lockedSelectionActive
                            ? buildLockedPositionSample(
                                    selectedMeasurementPath,
                                    lockedSelectionPosition
                            )
                            : null;

            /*
             * A projection outside the 20 m corridor returns null.
             * That means "current route position unknown", not "remove the
             * last known route position".
             */
            if (currentLockedPositionSample
                    != null) {

                lastLockedPositionSample =
                        currentLockedPositionSample;
            }

            view.setLockedPositionSample(
                    lockedSelectionActive
                            ? lastLockedPositionSample
                            : null
            );

            infoPresenter.setHeightStats(
                    ""
            );

            return;
        }

        view.setMarkedSelectionProfile(
                false
        );

        view.setVillageSamples(
                new ArrayList<>()
        );

        view.setLockedPositionSample(
                null
        );

        int bucketCount =
                Math.max(
                        1,
                        (int)
                                Math.ceil(
                                        height
                                                / ROW_BUCKET_PX
                                )
                );

        ScanCandidate[] rows =
                new ScanCandidate[
                        bucketCount
                        ];

        LatLng cameraCenter =
                map.getCameraPosition()
                        .target;

        double viewportRadiusM =
                visibleViewportRadiusM(
                        cameraCenter
                );

        for (CaminoRoute route
                : routes) {

            for (RouteTrack track
                    : route.tracks) {

                if (!trackCouldBeVisible(
                        track,
                        cameraCenter,
                        viewportRadiusM
                )) {
                    continue;
                }

                collectRightmostRows(
                        rows,
                        track,
                        width,
                        height
                );
            }
        }

        List<CaminoHeightProfileView.Sample> samples =
                buildSamples(
                        rows,
                        width,
                        height
                );

        if (samples.size()
                < 2) {

            view.clearProfile();

            infoPresenter.setHeightStats(
                    ""
            );

            return;
        }

        view.setSamples(
                samples
        );

        /*
         * The envelope may combine different Caminos on different screen rows,
         * so route-level ascent/descent totals would be meaningless here.
         */
        infoPresenter.setHeightStats(
                ""
        );
    }

    private List<CaminoHeightProfileView.Sample> buildSelectedMeasurementSamples(
            MeasurementPath path
    ) {
        List<CaminoHeightProfileView.Sample> result =
                new ArrayList<>();

        if (!hasUsableMeasurementPath(
                path
        )) {
            return result;
        }

        List<ProfilePoint> points =
                path.profilePoints;

        double firstDistanceM =
                points.get(
                        0
                ).distanceM;

        double lastDistanceM =
                points.get(
                        points.size() - 1
                ).distanceM;

        double spanM =
                lastDistanceM
                        - firstDistanceM;

        if (!Double.isFinite(
                spanM
        )
                || spanM <= 0.01) {

            return result;
        }

        /*
         * Walking direction on the vertical diagram:
         *
         *   START = physical bottom edge
         *   GOAL  = physical top edge
         *
         * The View fade-mask expects samples in visual top-to-bottom order.
         * Therefore iterate the route backwards while keeping slope semantics
         * in the real forward walking direction.
         */
        boolean firstVisualSample =
                true;

        for (int index = points.size() - 1;
                index >= 0;
                index--) {

            ProfilePoint point =
                    points.get(
                            index
                    );

            if (point == null
                    || !Double.isFinite(
                    point.distanceM
            )
                    || !Double.isFinite(
                    point.elevationM
            )) {

                continue;
            }

            double progress =
                    (
                            point.distanceM
                                    - firstDistanceM
                    )
                            / spanM;

            progress =
                    Math.max(
                            0.0,
                            Math.min(
                                    1.0,
                                    progress
                            )
                    );

            float yFraction =
                    (float)
                            (
                                    1.0
                                            - progress
                            );

            boolean reversedBreakBefore =
                    firstVisualSample
                            || (
                            index + 1
                                    < points.size()
                            && points.get(
                            index + 1
                    ).breakBefore
                    );

            result.add(
                    new CaminoHeightProfileView.Sample(
                            1.0f,
                            yFraction,
                            point.elevationM,
                            point.distanceM
                                    - firstDistanceM,
                            smoothedMeasurementSlopePercent(
                                    points,
                                    index
                            ),
                            reversedBreakBefore
                    )
            );

            firstVisualSample =
                    false;
        }

        return result;
    }


    private List<CaminoHeightProfileView.Sample> buildVillageSamples(
            MeasurementPath profilePath,
            MeasurementPath settlementPath
    ) {
        List<CaminoHeightProfileView.Sample> result =
                new ArrayList<>();

        if (!hasUsableMeasurementPath(
                profilePath
        )
                || settlementPath == null
                || settlementPath.timetableStops == null
                || settlementPath.timetableStops.isEmpty()) {

            return result;
        }

        List<ProfilePoint> points =
                profilePath.profilePoints;

        double firstDistanceM =
                points.get(
                        0
                ).distanceM;

        double lastDistanceM =
                points.get(
                        points.size() - 1
                ).distanceM;

        double spanM =
                lastDistanceM
                        - firstDistanceM;

        if (!Double.isFinite(
                spanM
        )
                || spanM <= 0.01) {

            return result;
        }

        for (CaminoTimetablePathStop stop
                : settlementPath.timetableStops) {

            if (stop == null
                    || !CaminoTimetablePlanBuilder.isVillagePlaceKey(
                    stop.placeKey
            )
                    || !Double.isFinite(
                    stop.chainageM
            )) {

                continue;
            }

            double routeChainageM =
                    Math.max(
                            0.0,
                            Math.min(
                                    profilePath.distanceM,
                                    stop.chainageM
                            )
                    );

            double profileDistanceM =
                    firstDistanceM
                            + Math.min(
                            spanM,
                            routeChainageM
                    );

            CaminoHeightProfileView.Sample sample =
                    villageSampleAtDistance(
                            points,
                            firstDistanceM,
                            spanM,
                            profileDistanceM
                    );

            if (sample != null) {
                result.add(
                        sample
                );
            }
        }

        return result;
    }


    private CaminoHeightProfileView.Sample villageSampleAtDistance(
            List<ProfilePoint> points,
            double firstDistanceM,
            double spanM,
            double targetDistanceM
    ) {
        ProfilePoint previous =
                null;

        for (ProfilePoint current
                : points) {

            if (current == null
                    || current.point == null
                    || !Double.isFinite(
                    current.distanceM
            )
                    || !Double.isFinite(
                    current.elevationM
            )) {

                continue;
            }

            if (previous == null) {
                previous =
                        current;

                if (targetDistanceM
                        <= current.distanceM) {

                    return villageSample(
                            current.distanceM,
                            current.elevationM,
                            firstDistanceM,
                            spanM
                    );
                }

                continue;
            }

            if (targetDistanceM
                    > current.distanceM) {

                previous =
                        current;

                continue;
            }

            double segmentSpanM =
                    current.distanceM
                            - previous.distanceM;

            if (current.breakBefore
                    || !Double.isFinite(
                    segmentSpanM
            )
                    || segmentSpanM <= 0.001) {

                ProfilePoint nearest =
                        Math.abs(
                                targetDistanceM
                                        - previous.distanceM
                        )
                                <= Math.abs(
                                current.distanceM
                                        - targetDistanceM
                        )
                                ? previous
                                : current;

                return villageSample(
                        nearest.distanceM,
                        nearest.elevationM,
                        firstDistanceM,
                        spanM
                );
            }

            double t =
                    (
                            targetDistanceM
                                    - previous.distanceM
                    )
                            / segmentSpanM;

            t =
                    Math.max(
                            0.0,
                            Math.min(
                                    1.0,
                                    t
                            )
                    );

            double elevationM =
                    previous.elevationM
                            + t
                            * (
                            current.elevationM
                                    - previous.elevationM
                    );

            return villageSample(
                    targetDistanceM,
                    elevationM,
                    firstDistanceM,
                    spanM
            );
        }

        if (previous == null) {
            return null;
        }

        return villageSample(
                previous.distanceM,
                previous.elevationM,
                firstDistanceM,
                spanM
        );
    }


    private CaminoHeightProfileView.Sample villageSample(
            double profileDistanceM,
            double elevationM,
            double firstDistanceM,
            double spanM
    ) {
        if (!Double.isFinite(
                profileDistanceM
        )
                || !Double.isFinite(
                elevationM
        )) {

            return null;
        }

        double progress =
                (
                        profileDistanceM
                                - firstDistanceM
                )
                        / spanM;

        progress =
                Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                progress
                        )
                );

        return new CaminoHeightProfileView.Sample(
                1.0f,
                (float)
                        (
                                1.0
                                        - progress
                        ),
                elevationM,
                profileDistanceM
                        - firstDistanceM,
                0.0,
                false
        );
    }


    private CaminoHeightProfileView.Sample buildLockedPositionSample(
            MeasurementPath path,
            LatLng position
    ) {
        if (!hasUsableMeasurementPath(
                path
        )
                || position == null) {

            return null;
        }

        List<ProfilePoint> points =
                path.profilePoints;

        double firstDistanceM =
                points.get(
                        0
                ).distanceM;

        double lastDistanceM =
                points.get(
                        points.size() - 1
                ).distanceM;

        double spanM =
                lastDistanceM
                        - firstDistanceM;

        if (!Double.isFinite(
                spanM
        )
                || spanM <= 0.01) {

            return null;
        }

        double bestOffsetM =
                Double.POSITIVE_INFINITY;

        double bestDistanceM =
                Double.NaN;

        double bestElevationM =
                Double.NaN;

        for (int index = 1;
                index < points.size();
                index++) {

            ProfilePoint a =
                    points.get(
                            index - 1
                    );

            ProfilePoint b =
                    points.get(
                            index
                    );

            if (a == null
                    || b == null
                    || a.point == null
                    || b.point == null
                    || b.breakBefore
                    || !Double.isFinite(
                    a.distanceM
            )
                    || !Double.isFinite(
                    b.distanceM
            )
                    || !Double.isFinite(
                    a.elevationM
            )
                    || !Double.isFinite(
                    b.elevationM
            )) {

                continue;
            }

            PositionProjection projection =
                    projectPositionToSegment(
                            position,
                            a.point,
                            b.point
                    );

            if (projection == null
                    || projection.offsetM
                    >= bestOffsetM) {

                continue;
            }

            bestOffsetM =
                    projection.offsetM;

            bestDistanceM =
                    a.distanceM
                            + projection.t
                            * (
                            b.distanceM
                                    - a.distanceM
                    );

            bestElevationM =
                    a.elevationM
                            + projection.t
                            * (
                            b.elevationM
                                    - a.elevationM
                    );
        }

        if (!Double.isFinite(
                bestDistanceM
        )) {

            for (ProfilePoint point
                    : points) {

                if (point == null
                        || point.point == null
                        || !Double.isFinite(
                        point.distanceM
                )
                        || !Double.isFinite(
                        point.elevationM
                )) {

                    continue;
                }

                double offsetM =
                        GeoMath.distanceMeters(
                                position,
                                point.point
                        );

                if (offsetM
                        >= bestOffsetM) {

                    continue;
                }

                bestOffsetM =
                        offsetM;

                bestDistanceM =
                        point.distanceM;

                bestElevationM =
                        point.elevationM;
            }
        }

        if (!Double.isFinite(
                bestDistanceM
        )
                || !Double.isFinite(
                bestElevationM
        )
                || !Double.isFinite(
                bestOffsetM
        )
                || bestOffsetM
                > LOCKED_POSITION_MAX_OFFSET_M) {

            /*
             * Important: "nearest" is not the same as "on route".
             *
             * Outside the configured corridor this projection is invalid.
             * The caller deliberately keeps the last valid locked-position
             * sample visible until GPS is on route again.
             */
            return null;
        }

        double progress =
                (
                        bestDistanceM
                                - firstDistanceM
                )
                        / spanM;

        progress =
                Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                progress
                        )
                );

        return new CaminoHeightProfileView.Sample(
                1.0f,
                (float)
                        (
                                1.0
                                        - progress
                        ),
                bestElevationM,
                bestDistanceM
                        - firstDistanceM,
                0.0,
                false
        );
    }


    private PositionProjection projectPositionToSegment(
            LatLng query,
            LatLng a,
            LatLng b
    ) {
        double refLat =
                Math.toRadians(
                        (
                                query.getLatitude()
                                        + a.getLatitude()
                                        + b.getLatitude()
                        )
                                / 3.0
                );

        double cosLat =
                Math.max(
                        0.20,
                        Math.cos(
                                refLat
                        )
                );

        double metresPerRad =
                6_371_000.0;

        double ax =
                Math.toRadians(
                        a.getLongitude()
                                - query.getLongitude()
                )
                        * metresPerRad
                        * cosLat;

        double ay =
                Math.toRadians(
                        a.getLatitude()
                                - query.getLatitude()
                )
                        * metresPerRad;

        double bx =
                Math.toRadians(
                        b.getLongitude()
                                - query.getLongitude()
                )
                        * metresPerRad
                        * cosLat;

        double by =
                Math.toRadians(
                        b.getLatitude()
                                - query.getLatitude()
                )
                        * metresPerRad;

        double vx =
                bx - ax;

        double vy =
                by - ay;

        double lengthSq =
                vx * vx
                        + vy * vy;

        double t =
                0.0;

        if (lengthSq > 1e-9) {
            t =
                    -(
                            ax * vx
                                    + ay * vy
                    )
                            / lengthSq;

            t =
                    Math.max(
                            0.0,
                            Math.min(
                                    1.0,
                                    t
                            )
                    );
        }

        double px =
                ax
                        + t * vx;

        double py =
                ay
                        + t * vy;

        return new PositionProjection(
                t,
                Math.hypot(
                        px,
                        py
                )
        );
    }


    private static final class PositionProjection {

        final double t;
        final double offsetM;

        PositionProjection(
                double t,
                double offsetM
        ) {
            this.t =
                    t;

            this.offsetM =
                    offsetM;
        }
    }


    private double smoothedMeasurementSlopePercent(
            List<ProfilePoint> points,
            int centerIndex
    ) {
        if (points == null
                || centerIndex < 0
                || centerIndex >= points.size()) {

            return 0.0;
        }

        ProfilePoint center =
                points.get(
                        centerIndex
                );

        double halfWindowM =
                SLOPE_WINDOW_M
                        / 2.0;

        int left =
                centerIndex;

        while (left > 0
                && center.distanceM
                - points.get(
                left
        ).distanceM < halfWindowM) {

            if (points.get(
                    left
            ).breakBefore) {

                break;
            }

            left--;
        }

        int right =
                centerIndex;

        while (right < points.size() - 1
                && points.get(
                right
        ).distanceM
                - center.distanceM < halfWindowM) {

            ProfilePoint next =
                    points.get(
                            right + 1
                    );

            if (next.breakBefore) {
                break;
            }

            right++;
        }

        ProfilePoint a =
                points.get(
                        left
                );

        ProfilePoint b =
                points.get(
                        right
                );

        double horizontalM =
                b.distanceM
                        - a.distanceM;

        if (!Double.isFinite(
                horizontalM
        )
                || horizontalM <= 0.5
                || !Double.isFinite(
                a.elevationM
        )
                || !Double.isFinite(
                b.elevationM
        )) {

            return 0.0;
        }

        return (
                b.elevationM
                        - a.elevationM
        )
                / horizontalM
                * 100.0;
    }


    private void collectRightmostRows(
            ScanCandidate[] rows,
            RouteTrack track,
            float width,
            float height
    ) {
        int usablePointCount =
                Math.min(
                        track.points.size(),
                        track.elevations.size()
                );

        if (usablePointCount
                < 2) {
            return;
        }

        double trackDistanceM =
                0.0;

        for (int segmentIndex = 0;
                segmentIndex
                        < usablePointCount - 1;
                segmentIndex++) {

            LatLng pointA =
                    track.points.get(
                            segmentIndex
                    );

            LatLng pointB =
                    track.points.get(
                            segmentIndex + 1
                    );

            double elevationA =
                    track.elevations.get(
                            segmentIndex
                    );

            double elevationB =
                    track.elevations.get(
                            segmentIndex + 1
                    );

            double segmentLengthM =
                    GeoMath.distanceMeters(
                            pointA,
                            pointB
                    );

            if (!Double.isFinite(
                    segmentLengthM
            )
                    || segmentLengthM
                    <= 0.0
                    || !Double.isFinite(
                    elevationA
            )
                    || !Double.isFinite(
                    elevationB
            )) {

                if (Double.isFinite(
                        segmentLengthM
                )
                        && segmentLengthM
                        > 0.0) {

                    trackDistanceM +=
                            segmentLengthM;
                }

                continue;
            }

            PointF screenA =
                    map.getProjection()
                            .toScreenLocation(
                                    pointA
                            );

            PointF screenB =
                    map.getProjection()
                            .toScreenLocation(
                                    pointB
                            );

            if (screenA
                    == null
                    || screenB
                    == null
                    || !Float.isFinite(
                    screenA.x
            )
                    || !Float.isFinite(
                    screenA.y
            )
                    || !Float.isFinite(
                    screenB.x
            )
                    || !Float.isFinite(
                    screenB.y
            )) {

                trackDistanceM +=
                        segmentLengthM;

                continue;
            }

            float minX =
                    Math.min(
                            screenA.x,
                            screenB.x
                    );

            float maxX =
                    Math.max(
                            screenA.x,
                            screenB.x
                    );

            float minY =
                    Math.min(
                            screenA.y,
                            screenB.y
                    );

            float maxY =
                    Math.max(
                            screenA.y,
                            screenB.y
                    );

            if (maxX
                    < 0.0f
                    || minX
                    > width
                    || maxY
                    < 0.0f
                    || minY
                    > height) {

                trackDistanceM +=
                        segmentLengthM;

                continue;
            }

            float dy =
                    screenB.y
                            - screenA.y;

            if (Math.abs(
                    dy
            )
                    < 0.01f) {

                float y =
                        (
                                screenA.y
                                        + screenB.y
                        )
                                / 2.0f;

                if (y >= 0.0f
                        && y <= height
                        && maxX >= 0.0f
                        && minX <= width) {

                    float x =
                            Math.max(
                                    0.0f,
                                    Math.min(
                                            width,
                                            maxX
                                    )
                            );

                    double t =
                            Math.abs(
                                    screenB.x
                                            - screenA.x
                            )
                                    < 0.01f
                                    ? 0.5
                                    : (
                                    x
                                            - screenA.x
                            )
                                    / (
                                    screenB.x
                                            - screenA.x
                            );

                    t =
                            Math.max(
                                    0.0,
                                    Math.min(
                                            1.0,
                                            t
                                    )
                            );

                    int row =
                            Math.max(
                                    0,
                                    Math.min(
                                            rows.length - 1,
                                            (int)
                                                    Math.floor(
                                                            y
                                                                    / ROW_BUCKET_PX
                                                    )
                                    )
                            );

                    ScanCandidate current =
                            rows[
                                    row
                                    ];

                    if (current
                            == null
                            || x
                            > current.screenX) {

                        rows[
                                row
                                ] =
                                new ScanCandidate(
                                        track,
                                        segmentIndex,
                                        x,
                                        y,
                                        elevationA
                                                + (
                                                elevationB
                                                        - elevationA
                                        )
                                                * t,
                                        track.baseChainageM
                                                + trackDistanceM
                                                + segmentLengthM
                                                * t
                                );
                    }
                }

                trackDistanceM +=
                        segmentLengthM;

                continue;
            }

            int firstRow =
                    Math.max(
                            0,
                            (int)
                                    Math.floor(
                                            Math.max(
                                                    0.0f,
                                                    minY
                                            )
                                                    / ROW_BUCKET_PX
                                    )
                    );

            int lastRow =
                    Math.min(
                            rows.length - 1,
                            (int)
                                    Math.floor(
                                            Math.min(
                                                    height - 0.001f,
                                                    maxY
                                            )
                                                    / ROW_BUCKET_PX
                                    )
                    );

            for (int row = firstRow;
                    row <= lastRow;
                    row++) {

                float scanY =
                        row
                                * ROW_BUCKET_PX
                                + ROW_BUCKET_PX
                                * 0.5f;

                double t =
                        (
                                scanY
                                        - screenA.y
                        )
                                / dy;

                if (t
                        < 0.0
                        || t
                        > 1.0) {
                    continue;
                }

                float x =
                        (float)
                                (
                                        screenA.x
                                                + (
                                                screenB.x
                                                        - screenA.x
                                        )
                                                * t
                                );

                if (x
                        < 0.0f
                        || x
                        > width) {
                    continue;
                }

                ScanCandidate current =
                        rows[
                                row
                                ];

                if (current
                        != null
                        && current.screenX
                        >= x) {
                    continue;
                }

                rows[
                        row
                        ] =
                        new ScanCandidate(
                                track,
                                segmentIndex,
                                x,
                                scanY,
                                elevationA
                                        + (
                                        elevationB
                                                - elevationA
                                )
                                        * t,
                                track.baseChainageM
                                        + trackDistanceM
                                        + segmentLengthM
                                        * t
                        );
            }

            trackDistanceM +=
                    segmentLengthM;
        }
    }

    private List<CaminoHeightProfileView.Sample> buildSamples(
            ScanCandidate[] rows,
            float width,
            float height
    ) {
        List<CaminoHeightProfileView.Sample> result =
                new ArrayList<>();

        int previousRow =
                -1;

        for (int row = 0;
                row < rows.length;
                row++) {

            ScanCandidate candidate =
                    rows[
                            row
                            ];

            if (candidate
                    == null) {
                continue;
            }

            /*
             * A large empty vertical gap means no visible Camino geometry joins
             * the two regions. Small gaps are connected into the envelope.
             */
            boolean breakBefore =
                    previousRow < 0
                            || (
                            row
                                    - previousRow
                    )
                            * ROW_BUCKET_PX
                            > 80.0f;

            double slopePercent =
                    smoothedTrackSlopePercent(
                            candidate.track,
                            candidate.pointIndex
                    );

            result.add(
                    new CaminoHeightProfileView.Sample(
                            candidate.screenX
                                    / width,
                            candidate.screenY
                                    / height,
                            candidate.elevationM,
                            candidate.distanceM,
                            slopePercent,
                            breakBefore
                    )
            );

            previousRow =
                    row;
        }

        return result;
    }

    private double smoothedTrackSlopePercent(
            RouteTrack track,
            int centerIndex
    ) {
        if (centerIndex
                < 0
                || centerIndex
                >= track.points.size()
                || centerIndex
                >= track.elevations.size()) {

            return 0.0;
        }

        double halfWindowM =
                SLOPE_WINDOW_M
                        / 2.0;

        int leftIndex =
                centerIndex;

        double leftDistanceM =
                0.0;

        while (leftIndex
                > 0
                && leftDistanceM
                < halfWindowM) {

            double segmentM =
                    GeoMath.distanceMeters(
                            track.points.get(
                                    leftIndex - 1
                            ),
                            track.points.get(
                                    leftIndex
                            )
                    );

            if (!Double.isFinite(
                    segmentM
            )
                    || segmentM
                    <= 0.0) {
                break;
            }

            leftDistanceM +=
                    segmentM;

            leftIndex--;
        }

        int rightIndex =
                centerIndex;

        double rightDistanceM =
                0.0;

        while (rightIndex + 1
                < track.points.size()
                && rightIndex + 1
                < track.elevations.size()
                && rightDistanceM
                < halfWindowM) {

            double segmentM =
                    GeoMath.distanceMeters(
                            track.points.get(
                                    rightIndex
                            ),
                            track.points.get(
                                    rightIndex + 1
                            )
                    );

            if (!Double.isFinite(
                    segmentM
            )
                    || segmentM
                    <= 0.0) {
                break;
            }

            rightDistanceM +=
                    segmentM;

            rightIndex++;
        }

        if (leftIndex
                == rightIndex) {
            return 0.0;
        }

        double leftElevationM =
                track.elevations.get(
                        leftIndex
                );

        double rightElevationM =
                track.elevations.get(
                        rightIndex
                );

        double runM =
                leftDistanceM
                        + rightDistanceM;

        if (!Double.isFinite(
                leftElevationM
        )
                || !Double.isFinite(
                rightElevationM
        )
                || !Double.isFinite(
                runM
        )
                || runM
                <= 1.0) {

            return 0.0;
        }

        return 100.0
                * (
                rightElevationM
                        - leftElevationM
        )
                / runM;
    }

    private boolean trackCouldBeVisible(
            RouteTrack track,
            LatLng cameraCenter,
            double viewportRadiusM
    ) {
        if (cameraCenter
                == null
                || viewportRadiusM
                <= 0.0) {
            return true;
        }

        return GeoMath.distanceMeters(
                cameraCenter,
                track.boundsCenter
        )
                <= viewportRadiusM
                + track.boundsRadiusM;
    }

    private double visibleViewportRadiusM(
            LatLng cameraCenter
    ) {
        if (cameraCenter
                == null) {
            return 0.0;
        }

        double radiusM =
                0.0;

        org.maplibre.android.geometry.VisibleRegion visibleRegion =
                map.getProjection()
                        .getVisibleRegion();

        radiusM =
                Math.max(
                        radiusM,
                        GeoMath.distanceMeters(
                                cameraCenter,
                                visibleRegion.farLeft
                        )
                );

        radiusM =
                Math.max(
                        radiusM,
                        GeoMath.distanceMeters(
                                cameraCenter,
                                visibleRegion.farRight
                        )
                );

        radiusM =
                Math.max(
                        radiusM,
                        GeoMath.distanceMeters(
                                cameraCenter,
                                visibleRegion.nearLeft
                        )
                );

        radiusM =
                Math.max(
                        radiusM,
                        GeoMath.distanceMeters(
                                cameraCenter,
                                visibleRegion.nearRight
                        )
                );

        return radiusM;
    }

    private int dp(
            int value
    ) {
        return Math.round(
                value
                        * activity
                        .getResources()
                        .getDisplayMetrics()
                        .density
        );
    }


    private static final class ScanCandidate {

        final RouteTrack track;
        final int pointIndex;
        final float screenX;
        final float screenY;
        final double elevationM;
        final double distanceM;

        ScanCandidate(
                RouteTrack track,
                int pointIndex,
                float screenX,
                float screenY,
                double elevationM,
                double distanceM
        ) {
            this.track =
                    track;

            this.pointIndex =
                    pointIndex;

            this.screenX =
                    screenX;

            this.screenY =
                    screenY;

            this.elevationM =
                    elevationM;

            this.distanceM =
                    distanceM;
        }
    }
}
