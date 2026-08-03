package org.aurex.core;

import java.util.Collections;
import java.util.List;

/**
 * Реестр всех переключателей мода.
 *
 * Правило проекта: каждая новая функция добавляет РОВНО одну константу сюда.
 * Благодаря этому всегда видно полный список возможностей мода, а экран
 * настроек и функция сброса строятся автоматически поверх этого списка.
 *
 * Сейчас реестр пуст — функциональность добавляется отдельными задачами.
 */
public final class AurexFeatures {

    private AurexFeatures() {
    }

    // Пример будущей записи (не раскомментировать без реализации функции):
    // public static final BoolPref SEND_READ_RECEIPTS = new BoolPref("send_read_receipts", true);

    private static final List<BoolPref> ALL = Collections.emptyList();

    /** Все булевы настройки мода. Используется для массового сброса и отладки. */
    public static List<BoolPref> all() {
        return ALL;
    }
}
