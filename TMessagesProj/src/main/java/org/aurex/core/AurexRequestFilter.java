package org.aurex.core;

import org.aurex.features.ghost.GhostRequestFilter;
import org.aurex.features.paid.PaidReactions;
import org.telegram.tgnet.TLObject;

/**
 * Диспетчер фильтров исходящих запросов.
 *
 * ПОЧЕМУ ОТДЕЛЬНЫЙ КЛАСС. Врезка в ConnectionsManager.sendRequestInternal должна
 * остаться ровно одной на весь мод: каждая новая врезка в апстрим — это лишний
 * конфликт при обновлении Telegram. Раньше AurexHooks звал GhostRequestFilter
 * напрямую, и любая вторая функция, которой нужен сетевой фильтр, потребовала бы
 * либо второй врезки, либо правки фасада. Теперь фасад зовёт диспетчер, а список
 * фильтров расширяется здесь, в коде мода.
 *
 * Порядок опроса значим только тем, что GhostRequestFilter обновляет своё окно
 * "читать при действиях" на собственных действиях пользователя, поэтому он идёт
 * первым и видит все запросы.
 */
public final class AurexRequestFilter {

    private AurexRequestFilter() {
    }

    /**
     * @return true, если запрос не должен уйти на сервер.
     */
    public static boolean shouldDrop(int accountId, TLObject request) {
        if (request == null) {
            return false;
        }
        if (GhostRequestFilter.shouldDrop(accountId, request)) {
            return true;
        }
        if (PaidReactions.shouldDropRequest(request)) {
            return true;
        }
        return false;
    }
}
