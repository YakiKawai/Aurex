package org.aurex.ui;

import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import org.aurex.features.spy.SpyChatMerger;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * Визуальная метка восстановленного сообщения: иконка корзины в строке времени.
 *
 * AyuGram помечает такое сообщение символом в тексте. Это просто, но у подхода
 * два минуса: текст сообщения перестаёт совпадать с оригиналом, а символ
 * выглядит инородно. Здесь метка нарисована как штатная иконка Telegram и
 * текста не касается.
 *
 * Место под иконку резервируется тем же способом, которым Telegram резервирует
 * место под пометку «изменено»: ширина строки времени участвует в измерении
 * баббла и в переносе последней строки текста. Поэтому иконка не может
 * наложиться на текст ни в одном типе сообщений.
 */
public final class AurexSpyMark {

    /**
     * Заполнитель, которым резервируется место в строке времени.
     *
     * U+2007 (figure space) — пробел шириной цифры: его ширина стабильна,
     * не зависит от локали и не съедается при вёрстке, в отличие от обычного
     * пробела на границе строки.
     */
    private static final String SPACER = "\u2007\u2007";

    /** Отступ между иконкой и временем, dp. */
    private static final int GAP = 2;

    private static Drawable icon;
    private static int iconColor;

    private AurexSpyMark() {
    }

    /** Нужно ли помечать это сообщение. */
    public static boolean isMarked(MessageObject object) {
        try {
            return SpyChatMerger.isRestored(object);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Добавляет к строке времени место под иконку.
     *
     * Пустую строку не трогаем: время не рисуется вовсе (спонсорские сообщения,
     * быстрые ответы, {@code notime}), значит и метке негде появиться.
     */
    public static String reserve(String timeString) {
        try {
            if (TextUtils.isEmpty(timeString) || timeString.startsWith(SPACER)) {
                return timeString;
            }
            return SPACER + timeString;
        } catch (Throwable e) {
            FileLog.e(e);
            return timeString;
        }
    }

    /**
     * Рисует иконку в зарезервированном месте.
     *
     * Вызывается из {@code ChatMessageCell} сразу после отрисовки времени, когда
     * система координат холста уже сдвинута в левый верхний угол строки времени.
     * Поэтому иконке достаточно локальных координат и не нужно знать ни
     * положение баббла, ни тип сообщения.
     *
     * Цвет и прозрачность берутся у самого времени: метка автоматически
     * совпадает с ним в любой теме, на обоях, на медиа и при выделении.
     *
     * @param timeHeight высота строки времени, {@code timeLayout.getHeight()}
     */
    public static void draw(Canvas canvas, MessageObject object, int timeHeight) {
        try {
            if (canvas == null || timeHeight <= 0 || !isMarked(object)) {
                return;
            }
            final Drawable drawable = icon();
            if (drawable == null) {
                return;
            }
            // Размер иконки выводим из фактически зарезервированной ширины, а не
            // из константы: при системном увеличении шрифта метка масштабируется
            // вместе со временем и не выходит за отведённое место.
            final float reserved = Theme.chat_timePaint.measureText(SPACER) - AndroidUtilities.dp(GAP);
            final int size = (int) Math.min(reserved, timeHeight);
            if (size <= 0) {
                return;
            }
            final int color = Theme.chat_timePaint.getColor();
            if (icon == null || color != iconColor) {
                iconColor = color;
                drawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            }
            drawable.setAlpha(Theme.chat_timePaint.getAlpha());
            final int top = (timeHeight - size) / 2;
            drawable.setBounds(0, top, size, top + size);
            drawable.draw(canvas);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static Drawable icon() {
        if (icon == null) {
            icon = ApplicationLoader.applicationContext.getResources()
                    .getDrawable(R.drawable.msg_aurex_deleted).mutate();
            iconColor = 0;
        }
        return icon;
    }
}
