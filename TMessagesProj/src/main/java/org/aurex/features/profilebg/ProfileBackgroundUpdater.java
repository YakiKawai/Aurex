package org.aurex.features.profilebg;

import android.content.Intent;

import org.telegram.messenger.FileLoader;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ImageUpdater;

import java.io.File;

/**
 * Выбор изображения для фона профиля.
 *
 * <p>Используется штатный {@link ImageUpdater} — ровно тот же механизм, что и у
 * «Выбрать фото» для аватара: то же меню ChatAttachAlert (галерея, камера, поиск),
 * тот же редактор кадрирования и те же анимации. Своего интерфейса здесь нет
 * принципиально: любая самописная галерея отставала бы от апстрима.
 *
 * <p>Отличие ровно одно: {@code setUploadAfterSelect(false)} — фон никуда не
 * отправляется, готовый файл забирается из кэша и кладётся в хранилище мода.
 */
final class ProfileBackgroundUpdater implements ImageUpdater.ImageUpdaterDelegate {

    private final BaseFragment fragment;
    private final ImageUpdater imageUpdater;

    private Runnable onChanged;

    ProfileBackgroundUpdater(BaseFragment fragment) {
        this.fragment = fragment;
        imageUpdater = new ImageUpdater(false, ImageUpdater.FOR_TYPE_USER, false);
        imageUpdater.parentFragment = fragment;
        imageUpdater.setDelegate(this);
        imageUpdater.setUploadAfterSelect(false);
    }

    void openMenu(Runnable onChanged) {
        this.onChanged = onChanged;
        imageUpdater.openMenu(false, () -> {
        }, dialog -> {
        }, ImageUpdater.TYPE_DEFAULT);
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        imageUpdater.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void didUploadPhoto(
        TLRPC.InputFile photo,
        TLRPC.InputFile video,
        double videoStartTimestamp,
        String videoPath,
        TLRPC.PhotoSize bigSize,
        TLRPC.PhotoSize smallSize,
        boolean isVideo,
        TLRPC.VideoSize emojiMarkup
    ) {
        if (bigSize == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final File source = FileLoader.getInstance(account).getPathToAttach(bigSize, true);
        ProfileBackground.set(account, source, () -> {
            if (onChanged != null) {
                onChanged.run();
            }
        });
    }
}
