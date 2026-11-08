package com.narezany.myvpn;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AnimationUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Большой кликабельный ключ: свечение, вращающиеся кольца, пружинка при нажатии
 * и разлетающиеся частицы. Служит и кнопкой подключения, и кликером.
 */
public class KeyClickerView extends View {

    private static final int CONNECTED_COLOR = 0xFF3DDC97;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();

    private int accent = 0xFF7C5CFF;
    private boolean connected;
    private float rotation;
    private float pulse;
    private float scale = 1f;
    private float targetScale = 1f;
    private long lastFrame;
    private Shader bodyShader;
    private Shader glowShader;
    private ValueAnimator accentAnimator;

    public KeyClickerView(final Context context) {
        super(context);
        init();
    }

    public KeyClickerView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public KeyClickerView(final Context context, final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint2.setStyle(Paint.Style.STROKE);
        keyPaint.setTextAlign(Paint.Align.CENTER);
        keyPaint.setTypeface(Typeface.DEFAULT);
        particlePaint.setStyle(Paint.Style.FILL);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    public void setAccent(final int color) {
        if (accent == color) return;
        accent = color;
        buildShaders();
        invalidate();
    }

    public void animateAccent(final int from, final int to) {
        if (accentAnimator != null) accentAnimator.cancel();
        accentAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        accentAnimator.setDuration(320);
        accentAnimator.addUpdateListener(animation -> {
            accent = (Integer) animation.getAnimatedValue();
            buildShaders();
            invalidate();
        });
        accentAnimator.start();
    }

    public void setConnected(final boolean value) {
        if (connected == value) return;
        connected = value;
        buildShaders();
        invalidate();
    }

    @Override
    protected void onSizeChanged(final int w, final int h, final int oldw, final int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildShaders();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrame = 0;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (accentAnimator != null) accentAnimator.cancel();
    }

    private void buildShaders() {
        final float radius = Math.min(getWidth(), getHeight()) / 2f - dp(3);
        if (radius <= 0) return;
        final int tint = connected ? CONNECTED_COLOR : accent;

        bodyShader = new RadialGradient(
                -radius * 0.18f, -radius * 0.22f, radius * 0.95f,
                new int[]{
                        Theme.blend(tint, 0xFFFFFFFF, 0.30f),
                        tint,
                        Theme.blend(tint, 0xFF000000, 0.38f)
                },
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP);

        glowShader = new RadialGradient(
                0f, 0f, radius * 1.08f,
                new int[]{withAlpha(tint, 120), withAlpha(tint, 40), withAlpha(tint, 0)},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);

        final long now = AnimationUtils.currentAnimationTimeMillis();
        final float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;

        rotation = (rotation + dt * 24f) % 360f;
        pulse += dt * 2.4f;
        scale += (targetScale - scale) * Math.min(1f, dt * 20f);
        updateParticles(dt);

        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float radius = Math.min(getWidth(), getHeight()) / 2f - dp(3);
        final int tint = connected ? CONNECTED_COLOR : accent;
        if (radius <= 0) return;

        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(scale, scale);
        canvas.translate(0f, (float) Math.sin(pulse) * dp(2.5f));

        // Мягкое свечение
        glowPaint.setShader(glowShader);
        glowPaint.setAlpha((int) (150 + 70 * Math.sin(pulse)));
        canvas.drawCircle(0f, 0f, radius * 1.08f, glowPaint);

        // Внешнее пунктирное кольцо
        ringPaint.setShader(null);
        ringPaint.setStrokeWidth(dp(2));
        ringPaint.setColor(withAlpha(tint, 200));
        ringPaint.setPathEffect(new DashPathEffect(new float[]{dp(9), dp(9)}, 0));
        canvas.save();
        canvas.rotate(rotation);
        canvas.drawCircle(0f, 0f, radius * 0.92f, ringPaint);
        canvas.restore();

        // Внутреннее кольцо крутится в обратную сторону
        ringPaint2.setShader(null);
        ringPaint2.setStrokeWidth(dp(1.5f));
        ringPaint2.setColor(withAlpha(tint, 90));
        ringPaint2.setPathEffect(null);
        canvas.save();
        canvas.rotate(-rotation * 1.6f);
        canvas.drawCircle(0f, 0f, radius * 0.76f, ringPaint2);
        canvas.restore();

        // Тело ключа
        bodyPaint.setShader(bodyShader);
        canvas.drawCircle(0f, 0f, radius * 0.62f, bodyPaint);
        ringPaint2.setColor(withAlpha(0xFFFFFFFF, 50));
        ringPaint2.setStrokeWidth(dp(1));
        canvas.drawCircle(0f, 0f, radius * 0.62f, ringPaint2);

        // Сам ключ
        keyPaint.setTextSize(radius * 0.66f);
        keyPaint.setColor(0xFFFFFFFF);
        final Paint.FontMetrics metrics = keyPaint.getFontMetrics();
        canvas.drawText("🔑", 0f, -(metrics.ascent + metrics.descent) / 2f, keyPaint);

        // Зелёная точка, когда туннель поднят
        if (connected) {
            final float dotX = radius * 0.40f;
            final float dotY = radius * 0.40f;
            dotPaint.setColor(CONNECTED_COLOR);
            canvas.drawCircle(dotX, dotY, dp(6), dotPaint);
            dotPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(dotX, dotY, dp(2.5f), dotPaint);
        }

        // Частицы
        for (final Particle particle : particles) {
            particlePaint.setColor(particle.color);
            particlePaint.setAlpha((int) (255 * Math.max(0f, particle.life / particle.maxLife)));
            canvas.drawCircle(particle.x, particle.y, particle.size, particlePaint);
        }

        canvas.restore();

        if (isShown()) postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(final MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                targetScale = 0.9f;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                targetScale = 1f;
                spawnParticles();
                performClick();
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                targetScale = 1f;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void spawnParticles() {
        final int tint = connected ? CONNECTED_COLOR : accent;
        for (int i = 0; i < 16; i++) {
            final double angle = random.nextDouble() * Math.PI * 2;
            final float speed = dp(60) + random.nextFloat() * dp(150);
            final Particle particle = new Particle();
            particle.x = 0f;
            particle.y = 0f;
            particle.vx = (float) Math.cos(angle) * speed;
            particle.vy = (float) Math.sin(angle) * speed - dp(30);
            particle.maxLife = 0.5f + random.nextFloat() * 0.35f;
            particle.life = particle.maxLife;
            particle.size = dp(2) + random.nextFloat() * dp(3);
            particle.color = i % 3 == 0 ? 0xFFFFFFFF : tint;
            particles.add(particle);
        }
    }

    private void updateParticles(final float dt) {
        for (final Iterator<Particle> iterator = particles.iterator(); iterator.hasNext(); ) {
            final Particle particle = iterator.next();
            particle.life -= dt;
            if (particle.life <= 0f) {
                iterator.remove();
                continue;
            }
            particle.x += particle.vx * dt;
            particle.y += particle.vy * dt;
            particle.vy += dp(220) * dt;
            particle.vx *= 0.985f;
        }
    }

    private float dp(final float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static int withAlpha(final int color, final int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static final class Particle {
        float x;
        float y;
        float vx;
        float vy;
        float life;
        float maxLife;
        float size;
        int color;
    }
}
