package com.fouu.habitflow.ui.analytics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.animation.ValueAnimator;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;

import com.fouu.habitflow.R;

import java.util.ArrayList;
import java.util.List;

/**
 * WeeklyBarChartView - Custom M3-styled bar chart for weekly completion data.
 *
 * Draws 7 bars (Mon-Sun) with rounded corners and a gradient fill.
 * Used in AnalyticsFragment.
 */
public class WeeklyBarChartView extends View {

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<AnalyticsViewModel.WeeklyDataPoint> data = new ArrayList<>();
    private float[] animatedRates;   // current animated heights (0..1), one per bar
    private float[] targetRates;     // target heights (0..1)
    private ValueAnimator animator;
    private int primaryColor;
    private int onSurfaceColor;
    private int surfaceVariantColor;
    private int errorColor;

    // When the bar count exceeds FILL_THRESHOLD the view lays bars out at a fixed
    // minimum width (so each stays tappable/readable) and reports a content width
    // larger than its parent — combined with a HorizontalScrollView this yields
    // a horizontally scrollable chart (used by the monthly view, ~30 bars).
    // At or below the threshold the view fills its parent (no scrolling).
    private static final int MIN_BAR_DP = 44;
    private static final int FILL_THRESHOLD = 7;
    private static final int PADDING = 40;

    public WeeklyBarChartView(Context context) {
        super(context);
        init(context);
    }

    public WeeklyBarChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        primaryColor = MaterialColors.getColor(context, android.R.attr.colorPrimary, 0);
        onSurfaceColor = MaterialColors.getColor(context, android.R.attr.colorForeground, 0);
        surfaceVariantColor = MaterialColors.getColor(context, android.R.attr.colorBackground, 0);
        errorColor = ContextCompat.getColor(context, R.color.error);

        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setColor(primaryColor);

        textPaint.setColor(onSurfaceColor);
        textPaint.setTextSize(28f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        axisPaint.setColor(surfaceVariantColor);
        axisPaint.setStrokeWidth(2f);
    }

    public void setData(List<AnalyticsViewModel.WeeklyDataPoint> data) {
        if (data == null) data = new ArrayList<>();
        boolean sizeChanged = (this.data.size() != data.size());
        this.data = data;
        if (sizeChanged) requestLayout(); // bar count changed → re-measure (fill vs scroll)
        if (data.isEmpty()) {
            // Placeholder / empty push: clear the bars.
            if (animator != null) animator.cancel();
            animatedRates = null;
            targetRates = null;
            invalidate();
            return;
        }
        // Smoothly animate from the CURRENT bar heights to the new targets. On first display
        // animatedRates is null → starts from 0, so the bars still "grow" in. On every later
        // refresh (tab switch / data change) it eases from wherever it is to the new values,
        // which is always smooth — never a hard jump, never a replay-from-0 flicker.
        animateTo(data);
    }

    /**
     * Smoothly animate every bar from its current height to its new target. Uses a plain
     * ease curve (no overshoot) so the numbers/bar heights never overshoot past the target
     * and look wrong, and repeated calls just re-ease to the latest data (no jank).
     */
    public void animateTo(List<AnalyticsViewModel.WeeklyDataPoint> points) {
        if (points == null) points = new ArrayList<>();
        this.data = points;
        int n = points.size();
        float[] newTargets = new float[n];
        for (int i = 0; i < n; i++) {
            newTargets[i] = Math.max(0f, Math.min(1f, points.get(i).completionRate));
        }
        // Skip the animation entirely when the targets haven't changed. Without this, every
        // tab-switch (onResume → refresh → postValue → setData) re-runs a 500ms ease even
        // though nothing moved, which read as "stuttering / one beat behind". Only animate
        // when at least one bar's target actually differs from what's already on screen.
        if (targetRates != null && targetRates.length == n) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                if (Math.abs(targetRates[i] - newTargets[i]) > 0.001f) { changed = true; break; }
            }
            if (!changed) {
                // Still make sure animatedRates reflects the targets (e.g. first paint after skip).
                if (animatedRates == null || animatedRates.length != n) {
                    animatedRates = new float[n];
                    System.arraycopy(newTargets, 0, animatedRates, 0, n);
                    invalidate();
                }
                return;
            }
        }
        if (animatedRates == null || animatedRates.length != n) {
            // First real data (or size changed): start from 0 so the bars grow in.
            animatedRates = new float[n];
            java.util.Arrays.fill(animatedRates, 0f);
        }
        final float[] from = new float[n];
        System.arraycopy(animatedRates, 0, from, 0, n);
        targetRates = newTargets;

        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(500);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            for (int i = 0; i < n; i++) {
                animatedRates[i] = from[i] + (targetRates[i] - from[i]) * t;
            }
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int count = data.size();
        int h = MeasureSpec.getSize(heightSpec);
        if (h <= 0) h = (int) (200 * getResources().getDisplayMetrics().density);
        int measuredW;
        if (count <= 0) {
            measuredW = MeasureSpec.getSize(widthSpec);
        } else if (count <= FILL_THRESHOLD) {
            // Fill the parent (no horizontal scroll) so weekly bars stretch nicely.
            measuredW = MeasureSpec.getSize(widthSpec);
        } else {
            // Content width = fixed min bar width per bar (+ padding on both sides).
            // With a HorizontalScrollView parent this becomes scrollable.
            float density = getResources().getDisplayMetrics().density;
            int contentW = (int) (count * MIN_BAR_DP * density) + 2 * PADDING;
            measuredW = Math.max(MeasureSpec.getSize(widthSpec), contentW);
        }
        setMeasuredDimension(measuredW, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (data.isEmpty()) return;

        int width = getWidth();
        int height = getHeight();
        int padding = PADDING;
        int chartWidth = width - 2 * padding;
        int chartHeight = height - 2 * padding - 30; // space for labels
        int barCount = data.size();
        int barWidth = chartWidth / (barCount * 2); // gap between bars
        int gap = barWidth;

        // Draw baseline
        canvas.drawLine(padding, height - padding, width - padding, height - padding, axisPaint);

        for (int i = 0; i < barCount; i++) {
            AnalyticsViewModel.WeeklyDataPoint point = data.get(i);
            float rate = (animatedRates != null && i < animatedRates.length)
                    ? animatedRates[i] : Math.max(0f, Math.min(1f, point.completionRate));

            // Completion rate <= 50% is "low" → red (matches the progress bars / rate label).
            boolean low = rate <= 0.5f;
            barPaint.setColor(low ? errorColor : primaryColor);
            int labelColor = low ? errorColor : onSurfaceColor;

            int left = padding + i * (barWidth + gap) + gap / 2;
            int right = left + barWidth;
            int barHeight = (int) (chartHeight * rate);
            int top = height - padding - barHeight;
            int bottom = height - padding;

            // Draw rounded bar
            android.graphics.RectF rect = new android.graphics.RectF(left, top, right, bottom);
            float radius = barWidth * 0.3f;
            canvas.drawRoundRect(rect, radius, radius, barPaint);

            // Day label (always on-surface color, independent of the bar's rate color)
            textPaint.setColor(onSurfaceColor);
            canvas.drawText(point.dayLabel, left + barWidth / 2f, height - padding + 25, textPaint);

            // Percentage on top of bar (use the animated rate so the number counts up too).
            // Same red/normal coloring as the rate label so low days read as "off track".
            if (rate > 0.05f) {
                String pct = String.format(java.util.Locale.US, "%d%%", (int) (rate * 100));
                textPaint.setColor(labelColor);
                canvas.drawText(pct, left + barWidth / 2f, top - 8, textPaint);
            }
        }
    }
}
