# Common Root?

_Studera texterna som formar vår värld._

<!-- AUTOGEN:meta -->
| Field | Value |
|---|---|
| Application | Common Root? |
| Version | 0.8.11 |
| Generated | 2026-09-22 |
<!-- /AUTOGEN:meta -->

## Vad det är

Common Root? är en fri, öppen läsare för att utforska de abrahamitiska
religionernas skrifter — bibelöversättningar, Koranen, hadith och kommentarer —
sida vid sida, på vilket språk som helst och på vilket djup som helst.

Namnet är en inbjudan. Läs dessa texter bredvid varandra och väg själv vad de
delar och var de skiljer sig åt.

Plattformen är oberoende. Den är inte knuten till någon religiös organisation
eller något förlag. Att läsa och studera är alltid gratis; ett valfritt gratis
konto ger dessutom möjligheten att lämna privata kommentarer och anteckningar.

## Vem den är till för

Forskare, studenter och nyfikna läsare lika gärna. Vare sig du jämför hur en
enskild vers återges i olika översättningar, följer en profetia genom båda
testamenten eller läser en bok som löpande prosa så som dess tidigaste publik
gjorde — läsaren är byggd för att hålla sig ur vägen.

## Hur läsningen fungerar

Läsaren är uppbyggd kring **kolumner**. Varje kolumn visar en text — en
bibelöversättning, Koranen, en kommentar eller dina egna anteckningar. Du kan
öppna så många kolumner du vill och blanda traditioner fritt. Kolumnerna rullar
ihop som standard så att samma avsnitt hålls i linje, och vilken kolumn som helst
kan kopplas loss för att bläddras självständigt.

### Visningslägen

En grundtanke i plattformen är att de indelningar vi tar för givna — kapitel,
verser, mellanrubriker — är redaktionella lager som lagts till långt efter att
texterna skrevs. Varje kolumn kan växlas mellan lägen som visar eller döljer dessa
lager:

- **Scriptio Continua** — löpande text i de forntida handskrifternas stil, utan
  kapitel- eller versindelning. Kapitlen i en bok flyter samman; en märkt linje
  markerar varje boks gräns.
- **Kapitel (1227)** — kapitelsystemet som Stephen Langton lade till omkring 1227.
- **Verser (1551)** — versnumren som Robert Estienne lade till 1551.
- **Rubriker** — moderna redaktionella mellanrubriker, där översättningen
  tillhandahåller dem.

### Läsordning

Varje kolumn kan också presentera sina böcker i olika ordningar: den traditionella
**kanoniska** ordningen, eller en vetenskaplig **kronologisk** ordning som
flätar samman avsnitt mellan böcker — så att profeterna och psalmerna kan läsas i
sitt historiska sammanhang i stället för isolerade.

### Att läsa Koranen

En Koran-kolumn visar den arabiska texten, och du kan välja en översättning att
visa under varje vers (aya). Arabiskan förblir det primära, med översättningen
parad under den i samma rullande kolumn så att de två aldrig glider isär.
Översättningsväljaren erbjuder endast översättningarna av just den Koranen.

### Delbara länkar

Läsarens fullständiga tillstånd — varje kolumns text, det avsnitt den är öppnad
vid, dess visningsläge och läsordning, ett eventuellt översättningslager och
huruvida kolumnerna rullar ihop — fångas i sidans URL. Kopiera länken för att
återvända till exakt samma vy senare, eller dela den med någon annan.
Hänvisningarna är översättnings- och språkoberoende, så en länk som skapats i en
översättning öppnas korrekt i en annan.

## Tillgängliga texter just nu

<!-- AUTOGEN:translations -->
_BaseX query skipped (install `requests` to enable live ingestion stats)._
<!-- /AUTOGEN:translations -->

Fler översättningar och traditioner — inklusive Koranen, hadith-samlingar och
ytterligare bibelversioner på många språk — läggs till löpande.

## Ljud per kapitel

Utgåvor i public domain får efter hand uppläst ljud: en MP3 per kapitel, plus ett
index med millisekundpositioner för varje vers, så att läsraden kan följa med och en
enskild vers går att länka till. Nuvarande täckning:

<!-- AUTOGEN:audio -->
_No audio manifest at `audio/index.json`; coverage table not refreshed. Set `COMMONROOT_AUDIO_INDEX` to point at it._
<!-- /AUTOGEN:audio -->

Det här är ett försök att över huvud taget få public domain-skrifter i ljudform,
inte en förlagsutgiven ljudbibel. Uppläsningen är syntetisk — neural talsyntes, en
röst per utgåva, ingen mänsklig uppläsare och ingen studiogenomgång — och den
skapas några kapitel per natt inom en gratis teckenkvot, så en utgåva i bibelstorlek
tar månader att fylla. Endast public domain-texter läses; utgåvor vars licens
förbjuder bearbetningar är medvetet uteslutna.

Poängen är att täcka texter som inte har något. Finska 1933 och Delitzsch hebreiska
Nya testamente saknar ljudutgåva värd namnet, medan engelskan är rikligt försörjd på
annat håll — därför står engelskan medvetet sist i kön i stället för först.

### Vad den maskinella uppläsningen gör fel

Värt att veta innan man lyssnar, och värt att rapportera när man hör det:

- **Egennamn.** Hebreiska och grekiska namn uttalas ofta fel. Versens text går till
  motorn som löpande text utan uttalslexikon, så ingenting rättar ett namn den
  gissar fel.
- **Ålderdomlig stavning.** Finskan från 1776 är äldre än modern ortografi, och
  motorns textnormalisering byggdes inte för den. Siffror, förkortningar och gamla
  stavningar läses som motorn nu läser dem.
- **En enda röst rakt igenom.** Berättande, dialog och citat delar samma tonläge;
  ljudet markerar aldrig vem som talar.
- **Verstiderna kan vara oprecisa.** Kapitel syntetiseras med flit som
  sammanhängande prosa, så att frasering bärs över versgränserna i stället för att
  stanna vid varje. Positionerna kommer från markörer som motorn rapporterar när
  den passerar varje vers och stämmer normalt exakt — men ett kapitel där den
  rapporterar färre markörer än kapitlet har verser publiceras ändå, och
  underskottet noteras bara i loggen. Några kapitel kan därför följa texten
  oprecist.
- **Skarvar i långa kapitel.** Allt över ungefär 7 500 tecken syntetiseras i delar
  och fogas samman. Skarvarna läggs vid versgränser, där en paus är naturlig, men
  någon gång hörs de.

Inget som redan genererats genereras om automatiskt — ett kapitel på disk är ett
kapitel som redan är betalt — så att rätta en dålig uppläsning är en medveten
handling: ta bort det kapitlet och låt nästa natts körning göra om det.

## Kommentarer och argument

Utöver primärtexterna samlar och indexerar plattformen argumentmaterial om
specifika verser, hämtat från en rad perspektiv inom de abrahamitiska
traditionerna och från kritisk analys. Materialet är länkat till de verser det
behandlar, så att en läsare som studerar ett omtvistat avsnitt kan se hur det
argumenteras från olika håll.

Källorna är ordnade efter kanal och perspektiv:

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

## Stöd

Common Root? är gratis och kommer alltid att vara det. Om den är till nytta för
dig hjälper ditt stöd till att täcka drift, tillgång till licensierade
översättningar och tiden att lägga till nya texter och funktioner.

Buy Me a Coffee: https://buymeacoffee.com/christaclaw
