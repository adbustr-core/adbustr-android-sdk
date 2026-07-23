package com.adbustr.sdk.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.adbustr.sdk.core.AdResponse;

/**
 * Mandatory Russian ad-marking badge ("Реклама · {advertiser}"), required
 * whenever a fill's {@code ord.marked} is false. Tapping expands the erid and
 * INN; tapping again collapses them.
 *
 * <p>Consumes its own touches so a tap on the badge is never counted as a click
 * on the creative — billing a click for a legally required label would be wrong.
 */
public final class OrdBadgeView extends LinearLayout {

    private final TextView detailsView;

    private boolean expanded;

    public OrdBadgeView(Context context, AdResponse.Ord ord) {
        super(context);

        setOrientation(VERTICAL);
        setGravity(Gravity.START);

        int padding = Ui.dp(context, 8);
        setPadding(padding, Ui.dp(context, 6), padding, Ui.dp(context, 6));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(190, 0, 0, 0));
        background.setCornerRadius(Ui.dp(context, 6));
        setBackground(background);

        TextView labelView = new TextView(context);
        labelView.setTextColor(Color.WHITE);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        labelView.setIncludeFontPadding(false);
        labelView.setText(label(ord));
        addView(labelView);

        detailsView = new TextView(context);
        detailsView.setTextColor(Color.argb(220, 255, 255, 255));
        detailsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        detailsView.setIncludeFontPadding(false);
        detailsView.setPadding(0, Ui.dp(context, 4), 0, 0);
        detailsView.setText(details(ord));
        detailsView.setVisibility(GONE);
        addView(detailsView);

        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                expanded = !expanded;
                detailsView.setVisibility(expanded ? VISIBLE : GONE);
            }
        });
        setClickable(true);
    }

    private static String label(AdResponse.Ord ord) {
        String advertiser = ord == null || ord.advertiser == null ? "" : ord.advertiser.trim();
        return advertiser.isEmpty() ? "Реклама ⓘ" : "Реклама · " + advertiser + " ⓘ";
    }

    private static String details(AdResponse.Ord ord) {
        if (ord == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (ord.erid != null && !ord.erid.isEmpty()) {
            text.append("erid: ").append(ord.erid);
        }
        if (ord.inn != null && !ord.inn.isEmpty()) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append("ИНН: ").append(ord.inn);
        }
        return text.toString();
    }

    /** True when this fill legally requires the badge to be rendered. */
    public static boolean isRequired(AdResponse.Ord ord) {
        return ord != null && !ord.marked;
    }
}
