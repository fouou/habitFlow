package com.fouu.habitflow.ui.habits;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Lightweight particle burst used to celebrate habit completion.
 * Call {@link #burst(int, int, int)} at a center point to emit particles.
 */
public class BurstView extends View {

    private static final int[] COLORS = {
            0xFF6200EE, 0xFF03DAC5, 0xFFFF4081, 0xFFFFAB00,
            0xFF4CAF50, 0xFF2196F3, 0xFFE91E63, 0xFFFF9800
    };

    private final List<Particle> particles = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private ValueAnimator animator;
    private long startTime;

    public BurstView(Context context) {
        super(context);
        init();
    }

    public BurstView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BurstView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
    }

    /** Emit a burst of particles centered at (cx, cy) in this view's coordinates. */
    public void burst(int cx, int cy, int count) {
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            float speed = 120 + random.nextFloat() * 180; // px/sec
            float vx = (float) (Math.cos(angle) * speed);
            float vy = (float) (Math.sin(angle) * speed) - 60; // slight upward bias
            int color = COLORS[random.nextInt(COLORS.length)];
            float radius = 4 + random.nextFloat() * 6;
            particles.add(new Particle(cx, cy, vx, vy, color, radius));
        }
        if (animator != null) animator.cancel();
        startTime = System.currentTimeMillis();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(700);
        animator.setInterpolator(new AccelerateInterpolator());
        animator.addUpdateListener(a -> invalidate());
        animator.start();
    }

    public void clear() {
        particles.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (particles.isEmpty()) return;
        float elapsed = (System.currentTimeMillis() - startTime) / 1000f;
        if (elapsed > 0.7f) {
            particles.clear();
            return;
        }
        float gravity = 600f; // px/sec^2
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            float x = p.x + p.vx * elapsed;
            float y = p.y + p.vy * elapsed + 0.5f * gravity * elapsed * elapsed;
            float alpha = 1f - elapsed / 0.7f;
            paint.setColor(p.color);
            paint.setAlpha((int) (alpha * 255));
            canvas.drawCircle(x, y, p.radius * (0.5f + alpha * 0.5f), paint);
        }
    }

    private static class Particle {
        float x, y, vx, vy, radius;
        int color;

        Particle(float x, float y, float vx, float vy, int color, float radius) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.color = color;
            this.radius = radius;
        }
    }
}
