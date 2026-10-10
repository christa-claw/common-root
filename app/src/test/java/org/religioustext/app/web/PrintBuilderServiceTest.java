// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.religioustext.app.config.BaseXConfig.BaseXProperties;
import org.religioustext.app.web.PrintBuilderService.Job;
import org.religioustext.app.web.PrintBuilderService.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrintBuilderServiceTest {

    @TempDir
    Path root;

    private static final BaseXProperties BASEX =
        new BaseXProperties("http://localhost:8984/rest", "admin", "admin", "religioustext");

    private static PrintPackageRequest.Valid valid() {
        return new PrintPackageRequest("WEB", "chronological", "66", "standard", null, null, null, null,
            null, null, null, null).validate();
    }

    private void withScript() throws IOException {
        Files.createDirectories(root.resolve("scripts/print"));
        Files.writeString(root.resolve("scripts/print/build_package.py"), "# stand-in");
    }

    /** A service whose "builder" is a shell snippet; it is given the path of the zip to make. */
    private PrintBuilderService service(final String setting, final String env,
                                        final BiFunction<PrintPackageRequest.Valid, Path, String> aShell,
                                        final long aTimeoutMillis) {
        return new PrintBuilderService(BASEX, setting, env, "", root.toString(), 20) {
            @Override
            protected List<String> buildCommand(final PrintPackageRequest.Valid aRequest, final Path anOut) {
                return List.of("/bin/sh", "-c", aShell.apply(aRequest, anOut));
            }

            @Override
            protected long timeoutMillis() { return aTimeoutMillis; }
        };
    }

    private static Job await(final Job aJob) throws InterruptedException {
        for (int i = 0; i < 200 && aJob.status() == Status.RUNNING; i++) Thread.sleep(50);
        return aJob;
    }

    @Test
    void aBuildThatSucceedsLeavesAZipAndItsResult() throws Exception {
        withScript();
        final PrintBuilderService s = service("true", "dev", (r, out) ->
            "echo building; printf PK > '" + out + "'; "
                + "echo 'RESULT {\"pages\":1166,\"spine_mm\":28,\"bytes\":2,\"sha256\":\"ab\",\"rights_status\":\"VERIFIED\"}'",
            60_000);
        final Job job = await(s.submit(valid()));
        assertEquals(Status.DONE, job.status(), job.error());
        assertEquals(1166, job.result().path("pages").asInt());
        assertTrue(Files.isRegularFile(job.zip()));
        assertTrue(job.tail(5).contains("building"));
        assertFalse(job.tail(5).stream().anyMatch(l -> l.startsWith("RESULT")));   // the result line is not log
        assertEquals("common-root-web-chronological-package.zip", job.filename());
    }

    @Test
    void aBuildThatFailsReportsItsLastWords() throws Exception {
        withScript();
        final Job job = await(service("true", "dev", (r, out) -> "echo thinking; echo 'No verses to set.'; exit 3", 60_000)
            .submit(valid()));
        assertEquals(Status.FAILED, job.status());
        assertTrue(job.error().contains("exit 3"));
        assertTrue(job.error().contains("No verses to set."));
    }

    @Test
    void aCleanExitWithNoResultIsStillAFailure() throws Exception {
        withScript();
        final Job job = await(service("true", "dev", (r, out) -> "echo done", 60_000).submit(valid()));
        assertEquals(Status.FAILED, job.status());
    }

    @Test
    void aBuildThatRunsTooLongIsKilled() throws Exception {
        withScript();
        final Job job = await(service("true", "dev", (r, out) -> "sleep 30", 300).submit(valid()));
        assertEquals(Status.FAILED, job.status());
        assertTrue(job.tail(5).stream().anyMatch(l -> l.contains("time limit")));
    }

    @Test
    void onlyOneBuildRunsAtATime() throws Exception {
        withScript();
        final PrintBuilderService s = service("true", "dev", (r, out) -> "sleep 1; printf PK > '" + out + "'; "
            + "echo 'RESULT {\"pages\":1}'", 60_000);
        final Job first = s.submit(valid());
        final PrintBuilderService.BusyException busy =
            assertThrows(PrintBuilderService.BusyException.class, () -> s.submit(valid()));
        assertEquals(first.id(), busy.runningId());
        assertEquals(Status.DONE, await(first).status());
        assertNotNull(s.submit(valid()));                 // free again once it has finished
    }

    @Test
    void itRunsOnlyWhereTheScriptIsAndNeverOnAProductionBuildByDefault() throws Exception {
        assertFalse(service("auto", "dev", (r, o) -> "", 1).enabled());            // no script yet
        withScript();
        assertTrue(service("auto", "dev", (r, o) -> "", 1).enabled());
        assertFalse(service("auto", "prod", (r, o) -> "", 1).enabled());           // the production jar
        assertFalse(service("false", "dev", (r, o) -> "", 1).enabled());           // switched off
        assertTrue(service("true", "prod", (r, o) -> "", 1).enabled());            // an explicit choice
    }

    @Test
    void anUnknownBuildHasNoJob() {
        assertEquals(null, service("true", "dev", (r, o) -> "", 1).job("nope"));
        assertEquals(null, service("true", "dev", (r, o) -> "", 1).job(null));
    }
}
