# Common Root?

_Studieren Sie die Texte, die unsere Welt prägen._

<!-- AUTOGEN:meta -->
| Field | Value |
|---|---|
| Application | Common Root? |
| Version | 0.8.11 |
| Generated | 2026-09-22 |
<!-- /AUTOGEN:meta -->

## Was es ist

Common Root? ist ein freier, offener Leser zum Erkunden der Schriften der
abrahamitischen Religionen — Bibelübersetzungen, der Koran, die Hadithe und
Kommentare — nebeneinander, in jeder Sprache und in jeder Tiefe.

Der Name ist eine Einladung. Lesen Sie diese Texte nebeneinander und wägen Sie
selbst ab, was sie teilen und wo sie auseinandergehen.

Die Plattform ist unabhängig. Sie ist mit keiner religiösen Organisation und
keinem Verlag verbunden. Lesen und Studieren sind immer kostenlos; ein optionales
kostenloses Konto bietet zusätzlich die Möglichkeit, private Kommentare und
Notizen zu hinterlassen.

## Für wen es ist

Für Gelehrte, Studierende und neugierige Leser gleichermaßen. Ob Sie vergleichen,
wie ein einzelner Vers über verschiedene Übersetzungen hinweg wiedergegeben wird,
eine Prophezeiung durch beide Testamente verfolgen oder ein Buch als fortlaufende
Prosa lesen, wie es seine frühesten Leser taten — der Leser ist so gebaut, dass
er Ihnen nicht im Weg steht.

## Wie das Lesen funktioniert

Der Leser ist um **Spalten** herum aufgebaut. Jede Spalte zeigt einen Text — eine
Bibelübersetzung, den Koran, einen Kommentar oder Ihre eigenen Notizen. Sie können
beliebig viele Spalten öffnen und Traditionen frei mischen. Die Spalten scrollen
standardmäßig gemeinsam, sodass dieselbe Stelle ausgerichtet bleibt, und jede
Spalte kann gelöst werden, um unabhängig zu blättern.

### Anzeigemodi

Ein Grundgedanke der Plattform ist, dass die Einteilungen, die wir für
selbstverständlich halten — Kapitel, Verse, Zwischenüberschriften —
redaktionelle Schichten sind, die lange nach der Niederschrift der Texte
hinzugefügt wurden. Jede Spalte lässt sich zwischen Modi umschalten, die diese
Schichten zeigen oder verbergen:

- **Scriptio Continua** — fortlaufender Text im Stil der antiken Handschriften,
  ohne Kapitel- oder Verseinteilung. Die Kapitel eines Buches laufen ineinander;
  eine beschriftete Linie markiert jede Buchgrenze.
- **Kapitel (1227)** — das Kapitelsystem, das Stephen Langton um 1227 hinzufügte.
- **Verse (1551)** — die Versnummern, die Robert Estienne 1551 hinzufügte.
- **Überschriften** — moderne redaktionelle Zwischenüberschriften, wo die
  Übersetzung sie bereitstellt.

### Lesereihenfolge

Jede Spalte kann ihre Bücher auch in verschiedenen Reihenfolgen darstellen: der
traditionellen **kanonischen** Reihenfolge oder einer wissenschaftlichen
**chronologischen** Reihenfolge, die Stellen über Bücher hinweg verschränkt — so
dass die Propheten und Psalmen in ihrem historischen Zusammenhang gelesen werden
können statt isoliert.

### Den Koran lesen

Eine Koran-Spalte zeigt den arabischen Text, und Sie können eine Übersetzung
wählen, die unter jedem Vers (Aya) angezeigt wird. Das Arabische bleibt
vorrangig, mit der Übersetzung darunter in derselben scrollenden Spalte gepaart,
sodass die beiden nie auseinanderdriften. Die Übersetzungsauswahl bietet nur die
Übersetzungen genau dieses Korans.

### Teilbare Links

Der vollständige Zustand des Lesers — der Text jeder Spalte, die geöffnete Stelle,
ihr Anzeigemodus und ihre Lesereihenfolge, eine etwaige Übersetzungsüberlagerung
und ob die Spalten gemeinsam scrollen — wird in der Seiten-URL erfasst. Kopieren
Sie den Link, um später zur exakt gleichen Ansicht zurückzukehren, oder teilen Sie
ihn mit jemandem. Die Verweise sind übersetzungs- und sprachunabhängig, sodass ein
in einer Übersetzung erstellter Link in einer anderen korrekt öffnet.

## Derzeit verfügbare Texte

<!-- AUTOGEN:translations -->
_BaseX query skipped (install `requests` to enable live ingestion stats)._
<!-- /AUTOGEN:translations -->

Weitere Übersetzungen und Traditionen — darunter der Koran, Hadith-Sammlungen und
zusätzliche Bibelversionen in vielen Sprachen — werden laufend hinzugefügt.

## Kapitel-Audio

Gemeinfreie Ausgaben erhalten nach und nach gesprochenes Audio: eine MP3 pro
Kapitel, dazu ein Index der Millisekunden-Zeitmarken je Vers, damit die Lesezeile
mitlaufen kann und ein einzelner Vers verlinkbar bleibt. Derzeitiger Stand:

<!-- AUTOGEN:audio -->
_No audio manifest at `audio/index.json`; coverage table not refreshed. Set `COMMONROOT_AUDIO_INDEX` to point at it._
<!-- /AUTOGEN:audio -->

Das ist ein Best-Effort-Vorhaben, gemeinfreie Schriften überhaupt als Audio
verfügbar zu machen, keine verlegerisch produzierte Hörbibel. Die Lesung ist
synthetisch — neuronale Sprachsynthese, eine Stimme je Ausgabe, kein menschlicher
Sprecher und kein Studiodurchgang — und sie entsteht Nacht für Nacht in wenigen
Kapiteln innerhalb eines kostenlosen Zeichenkontingents, sodass eine Ausgabe in
Bibelgröße Monate braucht. Gelesen werden ausschließlich gemeinfreie Texte;
Ausgaben, deren Lizenz Bearbeitungen untersagt, bleiben bewusst ausgeschlossen.

Es geht darum, Texte abzudecken, die nichts haben. Das finnische 1933er und das
hebräische Neue Testament von Delitzsch haben keine nennenswerte Audioausgabe,
während Englisch anderswo reichlich versorgt ist — deshalb steht Englisch in der
Warteschlange bewusst am Ende statt am Anfang.

### Was die maschinelle Lesung falsch macht

Gut zu wissen vor dem Hören, und meldenswert, wenn es auffällt:

- **Eigennamen.** Hebräische und griechische Namen werden häufig falsch
  ausgesprochen. Der Verstext geht als reiner Fließtext ohne Aussprachelexikon an
  die Engine, also korrigiert nichts einen falsch geratenen Namen.
- **Alte Rechtschreibung.** Das Finnische von 1776 ist älter als die moderne
  Orthografie, und die Textnormalisierung der Engine war nicht dafür gebaut.
  Zahlen, Abkürzungen und alte Schreibweisen werden gelesen, wie die Engine sie
  eben liest.
- **Durchgehend eine Stimme.** Erzählung, Dialog und Zitat teilen sich dieselbe
  Stimmlage; wer spricht, markiert das Audio nie.
- **Vers-Zeitmarken können ungenau sein.** Kapitel werden absichtlich als
  zusammenhängende Prosa synthetisiert, damit die Phrasierung über Versgrenzen
  hinweg trägt, statt an jeder zu stoppen. Die Zeitmarken stammen aus
  Markierungen, die die Engine beim Passieren jedes Verses meldet, und stimmen
  normalerweise genau — aber ein Kapitel, bei dem sie weniger Markierungen meldet
  als es Verse hat, wird trotzdem veröffentlicht, und der Fehlbetrag steht nur im
  Protokoll. Einzelne Kapitel laufen daher womöglich ungenau mit.
- **Nahtstellen in langen Kapiteln.** Alles über rund 7.500 Zeichen wird in Teilen
  synthetisiert und wieder zusammengefügt. Die Schnitte liegen an Versgrenzen, wo
  eine Pause natürlich ist, aber gelegentlich hört man eine.

Einmal erzeugtes Audio wird nie automatisch neu erzeugt — ein Kapitel auf der
Platte ist ein bereits bezahltes Kapitel —, eine schlechte Lesung zu korrigieren
ist also ein bewusster Schritt: dieses Kapitel löschen und vom nächsten
nächtlichen Lauf neu erzeugen lassen.

## Kommentar und Argumente

Über die Primärtexte hinaus sammelt und indiziert die Plattform Argumentmaterial
zu bestimmten Versen, das aus einer Reihe von Perspektiven innerhalb der
abrahamitischen Traditionen und aus kritischer Analyse stammt. Es ist mit den
Versen verknüpft, die es behandelt, sodass ein Leser, der eine umstrittene Stelle
studiert, sehen kann, wie aus verschiedenen Blickwinkeln darüber argumentiert
wird.

Die Quellen sind nach Kanal und Perspektive geordnet:

<!-- AUTOGEN:channels -->
| Channel | Tradition | Content |
|---|---|---|
| Apologetics Roadshow | Christian | shorts,videos,streams |
| GodLogic Apologetics | Christian | shorts,videos,streams |
| Hatun Tash DCCI Ministries | Christian | shorts,videos,streams |
| Israel Advocacy | Christian | shorts,videos,streams |
| Shamounian Explains | Christian | shorts,videos,streams |
| The Crucible | Christian | shorts,videos,streams |
| JihadWatchVideo | Critical | shorts,videos,streams |
| Raymond Ibrahim | Critical | shorts,videos,streams |
| Ali Dawah | Islamic | shorts,videos,streams |
| DUS Dawah | Islamic | shorts,videos,streams |
| DawahWise | Islamic | shorts,videos,streams |
| Dr Zakir Naik | Islamic | shorts,videos,streams |
| Let the Quran Speak | Islamic | shorts,videos,streams |
| Mohammed Hijab | Islamic | shorts,videos,streams |
| Modern Day Debate | Neutral | shorts,videos,streams |
| Alpha & Omega Ministries | Unknown | shorts,videos,streams |
| Apologia Studios | Unknown | shorts,videos,streams |
| Bible Thinker | Unknown | shorts,videos,streams |
| Bob of Speaker's Corner | Unknown | shorts,videos,streams |
| Christ Over ALL | Unknown | shorts,videos,streams |
| Cross Examined | Unknown | shorts,videos,streams |
| DCCI Ministries | Unknown | shorts,videos,streams |
| Elijah Johnson Apologetics | Unknown | shorts,videos,streams |
| Jay Dyer | Unknown | shorts,videos,streams |
| Maybe God Podcast | Unknown | shorts,videos,streams |
| One God One Truth HQ | Unknown | shorts,videos,streams |
| SO BE IT | Unknown | shorts,videos,streams |
| Theological Apologia | Unknown | shorts,videos,streams |
| Towards Eternity | Unknown | shorts,videos,streams |
| Vlad Savchuk | Unknown | shorts,videos,streams |

_30 channels configured._
<!-- /AUTOGEN:channels -->

## Unterstützung

Common Root? ist kostenlos und wird es immer bleiben. Wenn es Ihnen nützt, hilft
Ihre Unterstützung, Hosting, den Zugang zu lizenzierten Übersetzungen und die Zeit für
neue Texte und Funktionen zu decken.

Buy Me a Coffee: https://buymeacoffee.com/christaclaw
