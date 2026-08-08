package org.aurex.features.paid;

import org.aurex.core.AurexFeatures;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

/**
 * Локальный Telegram Premium.
 *
 * Перенос функции localPremium из AyuGram. Идея целиком её: сервер по-прежнему
 * считает аккаунт обычным, но клиент отвечает "да" на собственный вопрос
 * "я премиум?". Этого достаточно, чтобы снялись ограничения, которые Telegram
 * проверяет на устройстве: количество папок, длина описания и подписей,
 * количество реакций, премиум-стикеры, лимит историй.
 *
 * Функция сознательно НЕ трогает ничего, что проверяется на сервере: отправка
 * премиум-стикеров, эмодзи-статус, голосовые в текст и прочее по-прежнему
 * упрутся в ответ сервера. Так же ведёт себя и оригинал в AyuGram.
 *
 * Точки влияния в апстриме — ровно две, обе описаны в docs/PATCHES.md:
 *   UserConfig.isPremium()                  — "премиум ли я";
 *   MessagesController.isPremiumUser(User)  — "премиум ли вот этот пользователь".
 * Вторая нужна потому, что часть экранов спрашивает про пользователя, а не про
 * аккаунт. Она срабатывает только для собственного профиля: чужие аккаунты
 * премиум-статус от локальной настройки не получают.
 */
public final class LocalPremium {

    private LocalPremium() {
    }

    /** Включена ли функция. Общий флаг на весь клиент, как в AyuGram. */
    public static boolean isEnabled() {
        return AurexFeatures.LOCAL_PREMIUM.get();
    }

    /**
     * Считать ли данного пользователя премиумом из-за локальной настройки.
     *
     * Только собственный аккаунт: подменять статус собеседников нельзя, иначе
     * клиент начнёт рисовать чужим людям звёздочки и разрешать действия,
     * которые сервер тут же отклонит.
     */
    public static boolean isLocalPremiumUser(int accountId, TLRPC.User user) {
        if (user == null || !isEnabled()) {
            return false;
        }
        if (!UserConfig.isValidAccount(accountId)) {
            return false;
        }
        return user.id == UserConfig.getInstance(accountId).getClientUserId();
    }

    /**
     * Переключает функцию и приводит клиент в согласованное состояние.
     *
     * Набор действий — из AyuGram (toggleLocalPremium), дополненный сбросом
     * лимита историй: в нашей версии апстрима это штатная часть реакции на смену
     * премиум-статуса (см. UserConfig.checkPremiumSelf).
     *
     * Отличие от AyuGram: применяем ко всем активированным аккаунтам, а не
     * только к текущему. Настройка общая, поэтому isPremium() сразу меняется
     * везде; если не перестроить остальные аккаунты, у них до перезапуска
     * останутся заблокированные папки и старый лимит историй.
     *
     * Вызывать строго из главного потока: внутри рассылаются уведомления.
     */
    public static void setEnabled(boolean enabled) {
        if (AurexFeatures.LOCAL_PREMIUM.get() == enabled) {
            return;
        }
        AurexFeatures.LOCAL_PREMIUM.set(enabled);

        for (int accountId = 0; accountId < UserConfig.MAX_ACCOUNT_COUNT; accountId++) {
            if (!UserConfig.isValidAccount(accountId)) {
                continue;
            }
            if (!UserConfig.getInstance(accountId).isClientActivated()) {
                continue;
            }
            applyTo(accountId, enabled);
        }
    }

    /**
     * Побочные эффекты смены премиум-статуса для одного аккаунта.
     *
     * Каждый аккаунт обрабатывается независимо: сбой одного не должен мешать
     * остальным и тем более ронять экран настроек.
     */
    private static void applyTo(int accountId, boolean premium) {
        try {
            final MessagesController messagesController = MessagesController.getInstance(accountId);

            // Папки: при выключении лишние снова блокируются, при включении разблокируются.
            messagesController.updatePremium(premium);

            // Экраны, подписанные на смену статуса, перерисовываются.
            NotificationCenter.getInstance(accountId)
                    .postNotificationName(NotificationCenter.currentUserPremiumStatusChanged);
            NotificationCenter.getGlobalInstance()
                    .postNotificationName(NotificationCenter.premiumStatusChangedGlobal);

            final MediaDataController mediaDataController = MediaDataController.getInstance(accountId);
            // Промо-страница Premium зависит от статуса, поэтому запрашивается заново.
            mediaDataController.loadPremiumPromo(false);
            // Список реакций тоже: у премиума он шире. Нулевой хеш вместо сохранённого
            // заставляет сервер прислать полный список, а не "изменений нет".
            // Это точный аналог force = true из AyuGram: в нашей версии апстрима
            // второй параметр — не флаг, а хеш (Integer lastHash).
            mediaDataController.loadReactions(false, 0);

            // Лимит историй пересчитывается по новому статусу.
            messagesController.getStoriesController().invalidateStoryLimit();
        } catch (Throwable ignored) {
        }
    }
}
