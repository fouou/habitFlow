package com.fouu.habitflow.ui.habits;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Full-screen confetti that rains from the top. Call {@link #celebrate()} to start.
 */
public class ConfettiView extends View {

    private static final int[] COLORS = {
            0xFF6200EE, 0xFF03DAC5, 0xFFFF4081, 0xFFFFAB00,
            0xFF4CAF50, 0xFF2196F3, 0xFFE91E63, 0xFFFF9800, 0xFF9C27B0
    };

    private final List<Piece> pieces = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private boolean running = false;
    private long startTime;
    private final android.animation.ValueAnimator animator =
            android.animation.ValueAnimator.ofFloat(0f, 1f);

    public ConfettiView(Context context) {
        super(context);
        init();
    }

    public ConfettiView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ConfettiView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        animator.setDuration(2500);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(0);
        animator.addUpdateListener(a -> {
            if (running) invalidate();
        });
        // When the animator ends, no more invalidates fire, so any pieces still on screen
        // would freeze. Force a clear so the confetti disappears after it falls.
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                stop();
            }
        });
    }

    public void celebrate() {
        pieces.clear();
        int w = getWidth();
        int h = getHeight();
        if (h <= 0) h = 2000; // fallback if measured before layout
        int count = 90;
        // The whole show spans `total` seconds; pieces trickle in over the first
        // `maxDelay` seconds (each starts above the screen and rains down). fall speed
        // is derived from the screen height so EVERY piece crosses the FULL height
        // before the show ends — i.e. confetti falls from top all the way to the bottom.
        float total = 3.5f;
        float maxDelay = 1.2f;
        float baseFall = (h + 80) / (total - maxDelay);
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * w;
            float delay = random.nextFloat() * maxDelay;
            float fall = baseFall * (0.9f + random.nextFloat() * 0.2f); // 0.9..1.1
            float startY = -fall * delay;     // begins above the top, trickles in
            float drift = (random.nextFloat() - 0.5f) * 160;    // horizontal sway
            float spin = (random.nextFloat() - 0.5f) * 720;       // deg/sec
            int color = COLORS[random.nextInt(COLORS.length)];
            float wRect = 6 + random.nextFloat() * 8;
            float hRect = 10 + random.nextFloat() * 10;
            pieces.add(new Piece(x, startY, fall, drift, spin, color, wRect, hRect));
        }
        running = true;
        startTime = System.currentTimeMillis();
        // Generous safety cap; the self-stop in onDraw clears once every piece has
        // actually fallen past the bottom, usually before this fires.
        animator.setDuration((long) ((total + 0.5f) * 1000));
        animator.start();
    }

    public void stop() {
        running = false;
        pieces.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!running || pieces.isEmpty()) return;
        float elapsed = (System.currentTimeMillis() - startTime) / 1000f;
        float h = getHeight();
        boolean anyVisible = false;
        for (Piece p : pieces) {
            float y = p.y + p.fall * elapsed;
            float x = p.x + (float) Math.sin(elapsed * 2 + p.x) * p.drift * 0.1f + p.drift * elapsed;
            if (y > h + 20) continue;
            anyVisible = true;
            float angle = (p.spin * elapsed) % 360f;
            paint.setColor(p.color);
            canvas.save();
            canvas.rotate(angle, x, y);
            canvas.drawRoundRect(new RectF(x - p.w / 2, y - p.h / 2, x + p.w / 2, y + p.h / 2),
                    2f, 2f, paint);
            canvas.restore();
        }
        if (!anyVisible) {
            running = false;
            pieces.clear();
        }
    }

    private static class Piece {
        float x, y, fall, drift, spin, w, h;
        int color;

        Piece(float x, float y, float fall, float drift, float spin, int color, float w, float h) {
            this.x = x;
            this.y = y;
            this.fall = fall;
            this.drift = drift;
            this.spin = spin;
            this.color = color;
            this.w = w;
            this.h = h;
        }
    }
}
