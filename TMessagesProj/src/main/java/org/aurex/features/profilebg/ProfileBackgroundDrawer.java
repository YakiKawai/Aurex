package org.aurex.features.profilebg;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/**
 * Рисует фон шапки профиля.
 *
 * <p>Экземпляр принадлежит конкретному TopView: внутри хранятся Paint, Shader и Matrix,
 * чтобы в onDraw не было ни одной аллокации. Шейдер и градиент пересобираются только
 * при смене картинки или размеров шапки.
 *
 * <p>Картинка вписывается по center-crop, как штатные обои Telegram: масштаб по большей
 * стороне, остаток обрезается симметрично. Поверх ложится градиент затемнения к низу,
 * чтобы имя и статус оставались читаемыми на любом фото.
 */
public class ProfileBackgroundDrawer {

    private static final int SCRIM_COLOR_TOP = 0x00000000;
    private static final int SCRIM_COLOR_BOTTOM = 0x8A000000;
    private static final float SCRIM_START = 0.35f;

    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();

    private final View parent;
    private final Runnable invalidateParent;

    private Bitmap boundBitmap;
    private BitmapShader bitmapShader;
    private int lastWidth;
    private int lastHeight;
    private int scrimHeight;

    /**
     * @param parent вью, которую нужно перерисовать, когда фон догрузится с диска.
     */
    public ProfileBackgroundDrawer(View parent) {
        this.parent = parent;
        this.invalidateParent = () -> {
            if (this.parent != null) {
                this.parent.invalidate();
            }
        };
    }

    /**
     * @param account аккаунт, чей фон рисуем: у каждого свой.
     * @param alpha   прозрачность слоя, чтобы фон участвовал в анимациях открытия
     *                профиля так же, как штатный градиент.
     * @return true, если фон был отрисован.
     */
    public boolean draw(Canvas canvas, int account, int width, int height, float alpha) {
        if (canvas == null || width <= 0 || height <= 0 || alpha <= 0f) {
            return false;
        }
        final Bitmap bitmap = ProfileBackground.bitmap(account, invalidateParent);
        if (bitmap == null || bitmap.isRecycled()) {
            reset();
            return false;
        }
        if (bitmap != boundBitmap) {
            boundBitmap = bitmap;
            bitmapShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imagePaint.setShader(bitmapShader);
            lastWidth = 0;
            lastHeight = 0;
        }
        if (width != lastWidth || height != lastHeight) {
            lastWidth = width;
            lastHeight = height;
            final float scale = Math.max(
                (float) width / bitmap.getWidth(),
                (float) height / bitmap.getHeight()
            );
            matrix.reset();
            matrix.postScale(scale, scale);
            matrix.postTranslate(
                (width - bitmap.getWidth() * scale) / 2f,
                (height - bitmap.getHeight() * scale) / 2f
            );
            bitmapShader.setLocalMatrix(matrix);
        }
        imagePaint.setAlpha((int) (0xFF * alpha));
        canvas.drawRect(0, 0, width, height, imagePaint);

        if (height != scrimHeight) {
            scrimHeight = height;
            scrimPaint.setShader(new LinearGradient(
                0, height * SCRIM_START, 0, height,
                new int[]{SCRIM_COLOR_TOP, SCRIM_COLOR_BOTTOM},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP
            ));
        }
        scrimPaint.setAlpha((int) (0xFF * alpha));
        canvas.drawRect(0, 0, width, height, scrimPaint);
        return true;
    }

    private void reset() {
        if (boundBitmap == null) {
            return;
        }
        boundBitmap = null;
        bitmapShader = null;
        imagePaint.setShader(null);
        lastWidth = 0;
        lastHeight = 0;
    }
}
