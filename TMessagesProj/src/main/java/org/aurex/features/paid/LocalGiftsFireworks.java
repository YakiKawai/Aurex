package org.aurex.features.paid;

import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.FireworksOverlay;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

/**
 * Фейерверк после локальной отправки подарка.
 *
 * ЗАЧЕМ. Настоящая покупка NFT с витрины перепродажи заканчивается салютом:
 * штатный {@code ResaleGiftsFragment} держит у себя {@link FireworksOverlay} и
 * запускает его в ответ на успех оплаты. Наша локальная отправка сообщает об
 * успехе тем же колбэком, поэтому для NFT салют появляется сам, без единой
 * строки мода. А вот обычный подарок в апстриме отправляется через
 * {@code SendGiftSheet}, где салюта нет вообще — отсюда и разница, которую
 * видно на устройстве: у NFT эффект есть, у обычного подарка нет.
 *
 * ЧТО ДЕЛАЕТ ЭТОТ КЛАСС. Ровно то же, что апстрим делает у себя: берёт штатный
 * {@link FireworksOverlay}, кладёт его поверх текущего экрана и запускает с
 * теми же аргументами ({@code start(true)} — версия со звёздами, её же
 * использует и витрина перепродажи, и магазин звёзд). Своей анимации, своих
 * частиц, своих ресурсов у мода нет — есть только вызов готового компонента
 * Telegram.
 *
 * ПОЧЕМУ ОВЕРЛЕЙ НЕ УДАЛЯЕТСЯ. Так же устроен и апстрим: в
 * {@code ResaleGiftsFragment} и {@code StarsIntroActivity} оверлей живёт вместе
 * с экраном. Пока салют не запущен, это пустой прозрачный {@code View}: он
 * ничего не рисует и не перерисовывается, событий не перехватывает (не
 * clickable, поэтому касания уходят вниз, в чат). Удалять его по таймеру
 * означало бы обрывать анимацию на середине, если подарки отправляют один за
 * другим. Вместо этого повторная отправка находит уже добавленный оверлей и
 * перезапускает его — на экране никогда не появляется второй экземпляр.
 */
public final class LocalGiftsFireworks {

    private LocalGiftsFireworks() {
    }

    /**
     * Показывает салют поверх текущего экрана.
     *
     * Вызывать только из главного потока: метод работает с иерархией View.
     * Если подходящего экрана нет (приложение свернули, фрагмент уже закрылся),
     * метод просто ничего не делает — салют не критичен и не должен ничего
     * ломать.
     */
    public static void show() {
        try {
            final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            if (fragment == null) {
                return;
            }
            final FrameLayout container = containerOf(fragment);
            if (container == null) {
                return;
            }
            // Экран мог уже получить оверлей — свой (витрина перепродажи NFT,
            // магазин звёзд) или наш от прошлой отправки. Тогда перезапускаем
            // его, а не плодим новые.
            for (int i = container.getChildCount() - 1; i >= 0; i--) {
                final View child = container.getChildAt(i);
                if (child instanceof FireworksOverlay) {
                    ((FireworksOverlay) child).start(true);
                    return;
                }
            }
            final FireworksOverlay overlay = new FireworksOverlay(container.getContext());
            container.addView(overlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            overlay.start(true);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Контейнер для оверлея.
     *
     * Основной вариант — корневой View текущего экрана, как в апстриме. Он
     * почти всегда {@code FrameLayout} (у чата это {@code SizeNotifierFrameLayout}).
     * Если экран устроен иначе, берём корень активности: салют всё равно должен
     * появиться, но выдумывать для него собственную вёрстку мы не будем.
     */
    private static FrameLayout containerOf(BaseFragment fragment) {
        final View view = fragment.getFragmentView();
        if (view instanceof FrameLayout) {
            return (FrameLayout) view;
        }
        if (fragment.getParentActivity() == null) {
            return null;
        }
        final View content = fragment.getParentActivity().findViewById(android.R.id.content);
        return content instanceof FrameLayout ? (FrameLayout) content : null;
    }
}
