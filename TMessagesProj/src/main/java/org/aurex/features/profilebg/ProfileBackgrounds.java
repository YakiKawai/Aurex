package org.aurex.features.profilebg;

import android.content.Intent;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Единственная точка соприкосновения фона профиля с апстримом.
 *
 * <p>В {@code ProfileActivity} уходят только вызовы этого класса, вся логика живёт
 * в нашем пакете. Чем тоньше врезка, тем дешевле мерж новой версии Telegram и тем
 * проще будет переехать на {@code ProfileActivity2}, когда апстрим на него переключится.
 *
 * <p>Состояние экрана держит {@link Controller}: он знает про пункты меню, про
 * {@link ProfileBackgroundUpdater} и умеет обновлять подписи, не пересобирая меню.
 */
public final class ProfileBackgrounds {

    /**
     * Контроллеры живут ровно столько, сколько живёт фрагмент. Ссылка на фрагмент
     * внутри контроллера — слабая, иначе запись в WeakHashMap никогда бы не собралась.
     */
    private static final Map<BaseFragment, Controller> controllers = new WeakHashMap<>();

    private ProfileBackgrounds() {
    }

    /**
     * Добавляет пункты «Установить/Изменить фон» и «Удалить фон» в меню трёх точек.
     *
     * @param onChanged перерисовка шапки после смены или удаления фона.
     */
    public static void addMenuItems(
        BaseFragment fragment,
        ActionBarMenuItem otherItem,
        int setId,
        int removeId,
        Runnable onChanged
    ) {
        if (fragment == null || otherItem == null) {
            return;
        }
        controller(fragment).addMenuItems(otherItem, setId, removeId, onChanged);
    }

    public static void onMenuItemClick(BaseFragment fragment, int id) {
        final Controller controller = controllers.get(fragment);
        if (controller != null) {
            controller.onMenuItemClick(id);
        }
    }

    public static void onActivityResult(BaseFragment fragment, int requestCode, int resultCode, Intent data) {
        final Controller controller = controllers.get(fragment);
        if (controller != null) {
            controller.onActivityResult(requestCode, resultCode, data);
        }
    }

    private static Controller controller(BaseFragment fragment) {
        Controller controller = controllers.get(fragment);
        if (controller == null) {
            controller = new Controller(fragment);
            controllers.put(fragment, controller);
        }
        return controller;
    }

    private static final class Controller {

        private final WeakReference<BaseFragment> fragmentRef;

        private ProfileBackgroundUpdater updater;
        private ActionBarMenuItem otherItem;
        private ActionBarMenuSubItem setItem;
        private Runnable onChanged;
        private int setId;
        private int removeId;

        private Controller(BaseFragment fragment) {
            fragmentRef = new WeakReference<>(fragment);
        }

        private void addMenuItems(ActionBarMenuItem otherItem, int setId, int removeId, Runnable onChanged) {
            this.otherItem = otherItem;
            this.setId = setId;
            this.removeId = removeId;
            this.onChanged = onChanged;

            setItem = otherItem.addSubItem(setId, R.drawable.msg_aurex_background, title(hasBackground()));
            otherItem.addSubItem(removeId, R.drawable.msg_delete, LocaleController.getString(R.string.AurexProfileBackgroundRemove));
            otherItem.setSubItemShown(removeId, hasBackground());
        }

        private void onMenuItemClick(int id) {
            final BaseFragment fragment = fragmentRef.get();
            if (fragment == null) {
                return;
            }
            if (id == setId) {
                if (updater == null) {
                    updater = new ProfileBackgroundUpdater(fragment);
                }
                updater.openMenu(this::onBackgroundChanged);
            } else if (id == removeId) {
                ProfileBackground.remove(fragment.getCurrentAccount());
                onBackgroundChanged();
            }
        }

        private void onActivityResult(int requestCode, int resultCode, Intent data) {
            if (updater != null) {
                updater.onActivityResult(requestCode, resultCode, data);
            }
        }

        private void onBackgroundChanged() {
            final boolean has = hasBackground();
            if (setItem != null) {
                setItem.setText(title(has));
            }
            if (otherItem != null) {
                otherItem.setSubItemShown(removeId, has);
            }
            if (onChanged != null) {
                onChanged.run();
            }
        }

        private boolean hasBackground() {
            final BaseFragment fragment = fragmentRef.get();
            return fragment != null && ProfileBackground.has(fragment.getCurrentAccount());
        }

        private CharSequence title(boolean hasBackground) {
            return LocaleController.getString(hasBackground
                ? R.string.AurexProfileBackgroundChange
                : R.string.AurexProfileBackground);
        }
    }
}
