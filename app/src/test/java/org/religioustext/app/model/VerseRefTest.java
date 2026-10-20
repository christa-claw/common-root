// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** {@link VerseRef#clean(String)}: artefact stripping must never delete real verse text. */
class VerseRefTest {

    @Test
    void nullBecomesEmpty() {
        assertEquals("", VerseRef.clean(null));
    }

    @Test
    void stripsPilcrowsSectionMarksAndWhitespaceControls() {
        assertEquals("In the beginning God created", VerseRef.clean("¶ In the beginning\n\tGod §created "));
    }

    @Test
    void stripsTrailingFootnote() {
        assertEquals("And God divided the light from the darkness.",
            VerseRef.clean("And God divided the light from the darkness.1.4 Light: Heb. between the light and between the darkness."));
        assertEquals("a la tierra de Egipto.",
            VerseRef.clean("a la tierra de Egipto. 41.45 Es decir, Descubridor de lo oculto."));
    }

    @Test
    void verseBeginningWithItsOwnNumberIsNotAFootnote() {
        assertEquals("1.2 Doy gracias á mi Dios en toda memoria de vosotros,",
            VerseRef.clean("1.2 Doy gracias á mi Dios en toda memoria de vosotros,"));
    }

    @Test
    void rvr09InlineReferenceKeepsTheRestOfTheVerse() {
        assertEquals("Y la tierra estaba desordenada y vacía, y las tinieblas estaban sobre la haz del abismo, "
                + "y el Espíritu de Dios se movía sobre la haz de las aguas.",
            VerseRef.clean("Y la tierra estaba 1.2 Jer. 4.23. desordenada y vacía, y las tinieblas estaban sobre "
                + "la haz del abismo, y el Espíritu de Dios se movía sobre la haz de las aguas."));
    }

    @Test
    void rvr09ReferenceRunGluedToNextWord() {
        assertEquals("EN el principio crió Dios los cielos y la tierra.",
            VerseRef.clean("EN el 1.1 Sal. 136.5. Jn. 1.1-3. Col. 1.16,17. He. 1.10 y 11.3.principio crió Dios los cielos y la tierra."));
        assertEquals("Y dijo Dios: Sea la luz: y fué la luz.",
            VerseRef.clean("Y dijo Dios: 1.3 2 Co. 4.6.Sea la luz: y fué la luz."));
    }

    @Test
    void rvr09SeveralMarkersAndGlossesInOneVerse() {
        assertEquals("Y Rachêl hurtó los ídolos de su padre.",
            VerseRef.clean("Y Rachêl hurtó los 31.19 Heb. teraphim vers. 34.35. Jue. 17.5. 1 S. 15.23 y 19.13,16.ídolos de su padre."));
        assertEquals("Y SARAI, no le paría: y ella tenía una sierva egipcia, que se llamaba Agar.",
            VerseRef.clean("Y SARAI, 16.1 cp. 15.2,3.no le paría: y ella tenía una sierva 16.1 cp. 21.9.egipcia, "
                + "que se llamaba 16.1 Ga. 4.24.Agar."));
        assertEquals("Y sacóle fuera, y cuenta las estrellas.",
            VerseRef.clean("Y sacóle fuera, 15.5 Sal. 147.4.y cuenta las estrellas."));
    }

    @Test
    void rvr09TrailingReferenceRunOnly() {
        assertEquals("Texto del versículo.", VerseRef.clean("Texto del versículo. 1.9 Sal. 1.1."));
    }
}
