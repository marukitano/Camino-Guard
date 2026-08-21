package com.marukitano.caminoguard;

/**
 * Owns the text state rendered by CaminoInfoPanel.
 *
 * It contains no map, route, GPS, navigation or measurement algorithms.
 */
final class CaminoInfoPresenter {

    private CaminoInfoPanel infoPanel;

    private String infoTitleText =
            "";

    private String summaryLeftText =
            "";

    private String summaryRightText =
            "";

    private String heightStatsText =
            "";

    private String speedStatsText =
            "";

    void attach(
            CaminoInfoPanel infoPanel
    ) {
        this.infoPanel =
                infoPanel;

        refresh();
    }

    void setInfoTitle(
            String text
    ) {
        infoTitleText =
                text == null
                        ? ""
                        : text;

        refresh();
    }

    void setLabel(
            String text
    ) {
        setSummaryTexts(
                text,
                ""
        );
    }

    void setSummaryTexts(
            String left,
            String right
    ) {
        summaryLeftText =
                left == null
                        ? ""
                        : left;

        summaryRightText =
                right == null
                        ? ""
                        : right;

        refresh();
    }

    void setHeightStats(
            String text
    ) {
        heightStatsText =
                text == null
                        ? ""
                        : text;

        refresh();
    }

    void setSpeedStats(
            String text
    ) {
        speedStatsText =
                text == null
                        ? ""
                        : text;

        refresh();
    }

    void refresh() {
        if (infoPanel == null) {
            return;
        }

        infoPanel.setTitle(
                infoTitleText
        );

        infoPanel.setSummaryTexts(
                summaryLeftText,
                summaryRightText
        );

        infoPanel.setStatsTexts(
                heightStatsText,
                speedStatsText
        );
    }
}
