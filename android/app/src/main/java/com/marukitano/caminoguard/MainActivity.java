package com.marukitano.caminoguard;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static int dp(Activity activity, int value) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(this, 28), dp(this, 28), dp(this, 28), dp(this, 28));
        root.setBackgroundColor(Color.rgb(245, 241, 231));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(34);
        title.setTextColor(Color.rgb(28, 35, 31));
        title.setGravity(Gravity.CENTER);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.foundation_status);
        subtitle.setTextSize(18);
        subtitle.setTextColor(Color.rgb(70, 78, 72));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(this, 16), 0, 0);

        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }
}
