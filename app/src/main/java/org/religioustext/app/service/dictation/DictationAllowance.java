// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service.dictation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many dictation calls an ACCOUNT may make per UTC day. Each call spends money at an
 * outside service, which the API's request counter was never sized for: 20,000 requests a
 * month is nothing as passage reads and a real bill as model calls.
 *
 * <p>Counted per account, not per key, so five keys do not make five allowances. Kept in
 * memory: a restart hands everyone a fresh day, which errs toward the user and is bounded by
 * how seldom the app restarts.
 *
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
@Service
public class DictationAllowance {

    private record Day(LocalDate date, int used) { }

    private final int perDay;
    private final Clock clock;
    private final Map<String, Day> byAccount = new ConcurrentHashMap<>();

    /** @param aPerDay calls allowed per account per UTC day */
    @org.springframework.beans.factory.annotation.Autowired
    public DictationAllowance(@Value("${dictation.calls-per-day:50}") final int aPerDay) {
        this(aPerDay, Clock.systemUTC());
    }

    DictationAllowance(final int aPerDay, final Clock aClock) {
        this.perDay = aPerDay;
        this.clock  = aClock;
    }

    /** @return the daily allowance */
    public int perDay() { return perDay; }

    /**
     * Take one call from the account's day, if any is left.
     *
     * @param anAccountId the account's id
     * @return {@code false} when today's allowance is used up; nothing is taken then
     */
    public boolean tryTake(final String anAccountId) {
        final LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        final boolean[] taken = { false };
        byAccount.compute(anAccountId, (id, day) -> {
            final int used = day == null || !day.date().equals(today) ? 0 : day.used();
            if (used >= perDay) return new Day(today, used);
            taken[0] = true;
            return new Day(today, used + 1);
        });
        return taken[0];
    }
}
