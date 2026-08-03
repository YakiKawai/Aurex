package org.aurex.features.profilebg;

import android.app.Activity;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;

/**
 * Единственная точка соприкосновения с апстримом.
 *
 * <p>В {@code ProfileActivity} уходят только вызовы этого класса, вся логика живёт
 * в нашем пакете. Чем тоньше врезка, тем дешевле мерж новой версии Telegram и тем
 * проще будет переехать на {@code ProfileActivity2}, когда апстрим на него переключится.
 */
public final class ProfileBackgrounds {

    private ProfileBackgrounds() {
    }

    /**
     * Меню управления фоном в штатном BottomSheet.
     *
     * <p>Состав пунктов зависит от того, установлен ли фон: пункта-заглушки «удалить»
     * при пустом фоне не показываем.
     */
    public static void openMenu(BaseFragment fragment, Runnable onChanged) {
        if (fragment == null) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final boolean hasBackground = ProfileBackground.has(activity);

        final CharSequence[] items;
        final int[] icons;
        if (hasBackground) {
            items = new CharSequence[]{
                LocaleController.getString(R.string.AurexProfileBackgroundChange),
                LocaleController.getString(R.string.AurexProfileBackgroundRemove)
            };
            icons = new int[]{R.drawable.msg_photos, R.drawable.msg_delete};
        } else {
            items = new CharSequence[]{
                LocaleController.getString(R.string.AurexProfileBackgroundChoose)
            };
            icons = new int[]{R.drawable.msg_photos};
        }

        final BottomSheet.Builder builder = new BottomSheet.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.AurexProfileBackground), true);
        builder.setItems(items, icons, (dialog, which) -> {
            if (hasBackground && which == 1) {
                ProfileBackground.remove(activity);
                if (onChanged != null) {
                    onChanged.run();
                }
                return;
            }
            ProfileBackgroundPicker.openGallery(fragment, () -> {
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        });
        builder.show();
    }
}
