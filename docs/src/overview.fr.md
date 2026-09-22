# Common Root?

_Étudiez les textes qui façonnent notre monde._

<!-- AUTOGEN:meta -->
| Field | Value |
|---|---|
| Application | Common Root? |
| Version | 0.8.11 |
| Generated | 2026-09-22 |
<!-- /AUTOGEN:meta -->

## Ce que c'est

Common Root? est un lecteur libre et ouvert pour explorer les écritures des
religions abrahamiques — traductions de la Bible, le Coran, les hadiths et les
commentaires — côte à côte, dans n'importe quelle langue et à n'importe quel
niveau de profondeur.

Le nom est une invitation. Lisez ces textes les uns à côté des autres et jugez
par vous-même ce qu'ils partagent et là où ils divergent.

La plateforme est indépendante. Elle n'est affiliée à aucune organisation
religieuse ni à aucun éditeur. Lire et étudier est toujours gratuit ; un compte
gratuit facultatif ajoute la possibilité de laisser des commentaires et des notes
privés.

## À qui elle s'adresse

Aux chercheurs, aux étudiants comme aux lecteurs curieux. Que vous compariez la
façon dont un même verset est rendu d'une traduction à l'autre, que vous suiviez
une prophétie à travers les deux Testaments ou que vous lisiez un livre comme une
prose continue à la manière de son tout premier public — le lecteur est conçu
pour s'effacer devant vous.

## Comment se passe la lecture

Le lecteur s'organise autour de **colonnes**. Chaque colonne affiche un texte —
une traduction de la Bible, le Coran, un commentaire ou vos propres notes. Vous
pouvez ouvrir autant de colonnes que vous le souhaitez et mêler librement les
traditions. Les colonnes défilent ensemble par défaut afin que le même passage
reste aligné, et n'importe quelle colonne peut être dissociée pour être parcourue
indépendamment.

### Modes d'affichage

Une idée centrale de la plateforme est que les divisions que nous tenons pour
acquises — chapitres, versets, intertitres — sont des couches éditoriales
ajoutées longtemps après la rédaction des textes. Chaque colonne peut basculer
entre des modes qui révèlent ou masquent ces couches :

- **Scriptio Continua** — texte continu dans le style des manuscrits anciens, sans
  division en chapitres ni en versets. Les chapitres d'un livre s'enchaînent ; un
  filet étiqueté marque la limite de chaque livre.
- **Chapitres (1227)** — le système de chapitres ajouté par Stephen Langton vers
  1227.
- **Versets (1551)** — les numéros de versets ajoutés par Robert Estienne en 1551.
- **Titres** — les intertitres éditoriaux modernes, lorsque la traduction les
  fournit.

### Ordre de lecture

Chaque colonne peut aussi présenter ses livres dans différents ordres : l'ordre
**canonique** traditionnel, ou un ordre **chronologique** savant qui entrelace
les passages d'un livre à l'autre — afin que les prophètes et les psaumes puissent
être lus dans leur cadre historique plutôt qu'isolément.

### Lire le Coran

Une colonne du Coran affiche le texte arabe, et vous pouvez choisir une traduction
à afficher sous chaque verset (aya). L'arabe reste premier, la traduction étant
appariée en dessous dans la même colonne défilante, de sorte que les deux ne
s'écartent jamais. Le sélecteur de traduction n'offre que les traductions de ce
Coran-là.

### Liens partageables

L'état complet du lecteur — le texte de chaque colonne, le passage où elle est
ouverte, son mode d'affichage et son ordre de lecture, toute surimpression de
traduction et le fait que les colonnes défilent ou non ensemble — est capturé dans
l'URL de la page. Copiez le lien pour revenir exactement à la même vue plus tard,
ou partagez-le avec quelqu'un d'autre. Les références sont indépendantes de
l'édition et de la langue, si bien qu'un lien créé dans une traduction s'ouvre
correctement dans une autre.

## Textes actuellement disponibles

<!-- AUTOGEN:translations -->
_BaseX query skipped (install `requests` to enable live ingestion stats)._
<!-- /AUTOGEN:translations -->

D'autres traductions et traditions — dont le Coran, des recueils de hadiths et des
versions supplémentaires de la Bible en de nombreuses langues — sont ajoutées en
continu.

## Audio par chapitre

Les éditions du domaine public reçoivent peu à peu une version parlée : un MP3 par
chapitre, accompagné d'un index des décalages en millisecondes verset par verset,
pour que la ligne de lecture puisse suivre et qu'un verset reste lisible par lien.
État actuel :

<!-- AUTOGEN:audio -->
_No audio manifest at `audio/index.json`; coverage table not refreshed. Set `COMMONROOT_AUDIO_INDEX` to point at it._
<!-- /AUTOGEN:audio -->

Il s'agit d'un effort au mieux pour donner une forme audio aux Écritures du
domaine public, non d'une bible audio éditée. La lecture est synthétique — synthèse
vocale neuronale, une voix par édition, sans narrateur humain ni passage en studio
— et elle est produite quelques chapitres par nuit dans les limites d'un quota de
caractères gratuit : une édition de la taille d'une bible demande donc des mois.
Seuls des textes du domaine public sont lus ; les éditions dont la licence interdit
les œuvres dérivées sont volontairement exclues.

L'objectif est de couvrir les textes qui n'ont rien. Le finnois de 1933 et le
Nouveau Testament hébreu de Delitzsch n'ont aucune édition audio digne de ce nom,
tandis que l'anglais est abondamment servi ailleurs — d'où sa place volontairement
dernière, et non première, dans la file.

### Ce que la lecture automatique rate

Bon à savoir avant d'écouter, et utile à signaler quand on l'entend :

- **Les noms propres.** Les noms hébreux et grecs sont fréquemment mal prononcés.
  Le texte des versets est transmis au moteur en texte courant, sans lexique de
  prononciation : rien ne corrige un nom mal deviné.
- **L'orthographe ancienne.** Le finnois de 1776 est antérieur à l'orthographe
  moderne, et le normaliseur de texte du moteur n'a pas été conçu pour elle. Les
  nombres, les abréviations et les graphies anciennes sont lus comme le moteur les
  lit.
- **Une seule voix du début à la fin.** Narration, dialogue et citation partagent
  le même registre ; l'audio ne marque jamais qui parle.
- **Les repères de versets peuvent être imprécis.** Les chapitres sont synthétisés
  comme une prose continue, à dessein, pour que le phrasé franchisse les limites de
  versets au lieu de s'arrêter à chacune. Les décalages proviennent de marques que
  le moteur signale en passant chaque verset et sont normalement exacts — mais un
  chapitre où il signale moins de marques qu'il n'y a de versets est tout de même
  publié, l'écart n'étant noté que dans le journal. Quelques chapitres peuvent donc
  suivre imparfaitement.
- **Coutures des longs chapitres.** Au-delà d'environ 7 500 caractères, un chapitre
  est synthétisé en plusieurs parties puis rejoint. Les raccords sont placés aux
  limites de versets, là où une pause est naturelle, mais il arrive qu'on en
  entende un.

Rien de ce qui a été produit n'est reproduit automatiquement — un chapitre sur le
disque est un chapitre déjà payé — : corriger une mauvaise lecture est donc un
geste délibéré, supprimer ce chapitre et laisser la passe de la nuit suivante le
refaire.

## Commentaires et arguments

Au-delà des textes primaires, la plateforme rassemble et indexe des éléments
d'argumentation portant sur des versets précis, issus d'un éventail de
perspectives au sein des traditions abrahamiques et de l'analyse critique. Ils
sont reliés aux versets qu'ils traitent, de sorte qu'un lecteur étudiant un
passage contesté puisse voir comment il est argumenté sous différents angles.

Les sources sont organisées par chaîne et par perspective :

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

## Soutien

Common Root? est gratuit et le restera toujours. S'il vous est utile, votre
soutien aide à couvrir l'hébergement, l'accès aux traductions sous licence et
le temps consacré à l'ajout de nouveaux textes et fonctionnalités.

Buy Me a Coffee : https://buymeacoffee.com/christaclaw
