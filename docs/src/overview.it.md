# Common Root?

_Studia i testi che plasmano il nostro mondo._

<!-- AUTOGEN:meta -->
| Field | Value |
|---|---|
| Application | Common Root? |
| Version | 0.8.11 |
| Generated | 2026-09-22 |
<!-- /AUTOGEN:meta -->

## Che cos'è

Common Root? è un lettore libero e aperto per esplorare le scritture delle
religioni abramitiche — traduzioni della Bibbia, il Corano, gli hadith e i
commentari — affiancate, in qualsiasi lingua e a qualsiasi livello di
approfondimento.

Il nome è un invito. Leggi questi testi l'uno accanto all'altro e valuta tu stesso
ciò che condividono e dove divergono.

La piattaforma è indipendente. Non è affiliata ad alcuna organizzazione religiosa
né ad alcun editore. Leggere e studiare è sempre gratuito; un account gratuito
facoltativo aggiunge la possibilità di lasciare commenti e note private.

## A chi è rivolto

A studiosi, studenti e lettori curiosi allo stesso modo. Che tu stia confrontando
come uno stesso versetto venga reso nelle diverse traduzioni, seguendo una
profezia attraverso entrambi i Testamenti o leggendo un libro come prosa continua
nel modo in cui lo faceva il suo primo pubblico, il lettore è costruito per
togliersi di mezzo.

## Come funziona la lettura

Il lettore è costruito attorno alle **colonne**. Ogni colonna mostra un testo —
una traduzione della Bibbia, il Corano, un commentario o le tue stesse note. Puoi
aprire tutte le colonne che vuoi e mescolare liberamente le tradizioni. Le colonne
scorrono insieme per impostazione predefinita, così che lo stesso passo resti
allineato, e qualsiasi colonna può essere scollegata per essere sfogliata in modo
indipendente.

### Modalità di visualizzazione

Un'idea centrale della piattaforma è che le suddivisioni che diamo per scontate —
capitoli, versetti, intertitoli — sono strati editoriali aggiunti molto tempo dopo
la stesura dei testi. Ogni colonna può passare tra modalità che rivelano o
nascondono questi strati:

- **Scriptio Continua** — testo continuo nello stile dei manoscritti antichi, senza
  divisioni in capitoli o versetti. I capitoli di un libro scorrono uniti; una riga
  etichettata segna il confine di ogni libro.
- **Capitoli (1227)** — il sistema dei capitoli aggiunto da Stephen Langton intorno
  al 1227.
- **Versetti (1551)** — i numeri dei versetti aggiunti da Robert Estienne nel 1551.
- **Titoli** — gli intertitoli editoriali moderni, dove la traduzione li fornisce.

### Ordine di lettura

Ogni colonna può anche presentare i suoi libri in ordini diversi: il tradizionale
ordine **canonico**, oppure un ordine **cronologico** accademico che intreccia i
passi tra i libri — così che i profeti e i salmi possano essere letti nel loro
contesto storico anziché isolati.

### Leggere il Corano

Una colonna del Corano mostra il testo arabo, e puoi scegliere una traduzione da
mostrare sotto ogni versetto (aya). L'arabo resta primario, con la traduzione
abbinata sotto di esso nella stessa colonna scorrevole, così che i due non si
allontanino mai. Il selettore di traduzione offre solo le traduzioni di quel
Corano.

### Link condivisibili

Lo stato completo del lettore — il testo di ogni colonna, il passo a cui è aperta,
la sua modalità di visualizzazione e l'ordine di lettura, qualsiasi sovrapposizione
di traduzione e se le colonne scorrono insieme — è racchiuso nell'URL della
pagina. Copia il link per tornare in seguito esattamente alla stessa vista, oppure
condividilo con qualcun altro. I riferimenti sono indipendenti dall'edizione e
dalla lingua, perciò un link creato in una traduzione si apre correttamente in
un'altra.

## Testi attualmente disponibili

<!-- AUTOGEN:translations -->
_BaseX query skipped (install `requests` to enable live ingestion stats)._
<!-- /AUTOGEN:translations -->

Altre traduzioni e tradizioni — tra cui il Corano, le raccolte di hadith e ulteriori
versioni della Bibbia in molte lingue — vengono aggiunte di continuo.

## Audio dei capitoli

Le edizioni di pubblico dominio ricevono man mano un audio parlato: un MP3 per
capitolo, più un indice degli scarti in millisecondi versetto per versetto, perché
la riga di lettura possa seguire il testo e un singolo versetto resti
collegabile. Copertura attuale:

<!-- AUTOGEN:audio -->
_No audio manifest at `audio/index.json`; coverage table not refreshed. Set `COMMONROOT_AUDIO_INDEX` to point at it._
<!-- /AUTOGEN:audio -->

È un tentativo fatto al meglio delle possibilità per dare una forma audio alle
Scritture di pubblico dominio, non una bibbia audio editoriale. La lettura è
sintetica — sintesi vocale neurale, una voce per edizione, nessun narratore umano e
nessun passaggio in studio — e viene prodotta pochi capitoli per notte entro una
quota gratuita di caratteri, così che un'edizione grande quanto una bibbia richiede
mesi. Vengono letti soltanto testi di pubblico dominio; le edizioni la cui licenza
vieta le opere derivate sono escluse di proposito.

Lo scopo è coprire i testi che non hanno nulla. Il finlandese del 1933 e il Nuovo
Testamento ebraico di Delitzsch non hanno un'edizione audio degna del nome, mentre
l'inglese è ampiamente servito altrove: per questo l'inglese è deliberatamente
ultimo in coda, e non primo.

### Che cosa sbaglia la lettura automatica

Utile saperlo prima di ascoltare, e utile segnalarlo quando si sente:

- **Nomi propri.** I nomi ebraici e greci sono spesso pronunciati male. Il testo del
  versetto arriva al motore come testo corrente, senza lessico di pronuncia: nulla
  corregge un nome indovinato male.
- **Ortografia arcaica.** Il finlandese del 1776 precede l'ortografia moderna e il
  normalizzatore del motore non è stato costruito per essa. Numeri, abbreviazioni e
  grafie antiche vengono letti come il motore li legge.
- **Una sola voce per tutto.** Narrazione, dialogo e citazione condividono lo stesso
  registro; l'audio non segnala mai chi parla.
- **I tempi dei versetti possono essere imprecisi.** I capitoli sono sintetizzati
  come prosa continua di proposito, così che il fraseggio attraversi le divisioni
  fra versetti invece di fermarsi a ognuna. Gli scarti derivano da marcatori che il
  motore segnala passando ogni versetto e di norma sono esatti — ma un capitolo in
  cui ne segnala meno di quanti versetti ci siano viene pubblicato lo stesso, e la
  differenza resta annotata solo nel log. Alcuni capitoli possono quindi seguire il
  testo in modo impreciso.
- **Giunture nei capitoli lunghi.** Tutto ciò che supera i 7.500 caratteri circa
  viene sintetizzato in parti e poi ricomposto. Le giunzioni cadono ai confini fra
  versetti, dove una pausa è naturale, ma ogni tanto se ne sente una.

Nulla di già generato viene rigenerato automaticamente — un capitolo su disco è un
capitolo già pagato —, quindi correggere una cattiva lettura è un atto deliberato:
cancellare quel capitolo e lasciare che la sessione della notte successiva lo
rifaccia.

## Commentari e argomenti

Oltre ai testi primari, la piattaforma raccoglie e indicizza materiale
argomentativo su versetti specifici, tratto da una gamma di prospettive all'interno
delle tradizioni abramitiche e dall'analisi critica. È collegato ai versetti di cui
tratta, così che un lettore che studi un passo controverso possa vedere come viene
argomentato da diverse angolazioni.

Le fonti sono organizzate per canale e prospettiva:

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

## Sostegno

Common Root? è gratuito e lo sarà sempre. Se ti è utile, il sostegno aiuta a
coprire l'hosting, l'accesso alle traduzioni su licenza e il tempo per aggiungere
nuovi testi e funzionalità.

Buy Me a Coffee: https://buymeacoffee.com/christaclaw
