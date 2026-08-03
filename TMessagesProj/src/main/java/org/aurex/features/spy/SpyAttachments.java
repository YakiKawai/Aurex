package org.aurex.features.spy;

import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Хранилище вложений модуля «Шпион».
 *
 * Отличие от AyuGram: файлы лежат во внутреннем хранилище приложения
 * (files/aurex/spy), а не в публичной папке Downloads. Причины:
 *  - на Android 11+ публичная папка требует разрешений и доступна другим приложениям;
 *  - при удалении приложения мусор не остаётся в галерее пользователя;
 *  - не нужен файл .nomedia, потому что внутреннее хранилище не сканируется.
 * Поведение для пользователя при этом не меняется.
 */
public final class SpyAttachments {

    public static final int DOCUMENT_TYPE_NONE = 0;
    public static final int DOCUMENT_TYPE_PHOTO = 1;
    public static final int DOCUMENT_TYPE_STICKER = 2;
    public static final int DOCUMENT_TYPE_FILE = 3;

    private static final String DIRECTORY = "aurex";
    private static final String SUBDIRECTORY = "spy";
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private SpyAttachments() {
    }

    /** Папка хранилища; создаётся при первом обращении. */
    public static File directory() {
        File root = new File(ApplicationLoader.getFilesDirFixed(), DIRECTORY);
        File dir = new File(root, SUBDIRECTORY);
        if (!dir.exists() && !dir.mkdirs()) {
            FileLog.e("Aurex: не удалось создать папку вложений " + dir);
        }
        return dir;
    }

    /**
     * Копирует файл в хранилище мода.
     *
     * Имя формируется из аккаунта, диалога и сообщения, поэтому повторный вызов для
     * того же сообщения возвращает тот же файл и не плодит дубликаты.
     *
     * @return абсолютный путь к копии или null, если копировать нечего/не удалось.
     */
    public static String store(File source, long userId, long dialogId, int messageId, String suffix) {
        if (source == null || !source.exists() || source.length() == 0) {
            return null;
        }
        String name = userId + "_" + dialogId + "_" + messageId + "_" + Math.abs(source.getName().hashCode());
        if (!TextUtils.isEmpty(suffix)) {
            name = name + suffix;
        }
        File target = new File(directory(), name);
        if (target.exists() && target.length() == source.length()) {
            return target.getAbsolutePath();
        }
        if (!copy(source, target)) {
            return null;
        }
        return target.getAbsolutePath();
    }

    private static boolean copy(File source, File target) {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return true;
        } catch (IOException e) {
            FileLog.e(e);
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return false;
        }
    }

    /** Удаляет файл, если он принадлежит хранилищу мода (чужие файлы не трогаем). */
    public static void remove(String path) {
        if (TextUtils.isEmpty(path)) {
            return;
        }
        File file = new File(path);
        if (!file.exists()) {
            return;
        }
        String owned = directory().getAbsolutePath();
        if (!file.getAbsolutePath().startsWith(owned)) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** Суммарный размер сохранённых вложений в байтах. */
    public static long totalSize() {
        long total = 0;
        File[] files = directory().listFiles();
        if (files != null) {
            for (File file : files) {
                total += file.length();
            }
        }
        return total;
    }

    /** Полная очистка хранилища вложений. */
    public static void clear() {
        File[] files = directory().listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
