# Common Root?

_Estudia los textos que dan forma a nuestro mundo._

<!-- AUTOGEN:meta -->
| Field | Value |
|---|---|
| Application | Common Root? |
| Version | 0.8.11 |
| Generated | 2026-09-22 |
<!-- /AUTOGEN:meta -->

## Qué es

Common Root? es un lector libre y abierto para explorar las escrituras de las
religiones abrahámicas — traducciones de la Biblia, el Corán, los hadices y los
comentarios — una al lado de la otra, en cualquier idioma y con cualquier nivel
de profundidad.

El nombre es una invitación. Lee estos textos unos junto a otros y juzga por ti
mismo qué comparten y en qué divergen.

La plataforma es independiente. No está afiliada a ninguna organización religiosa
ni a ninguna editorial. Leer y estudiar es siempre gratuito; una cuenta gratuita
opcional añade la posibilidad de dejar comentarios y notas privados.

## Para quién es

Para estudiosos, estudiantes y lectores curiosos por igual. Ya sea que compares
cómo se traduce un mismo versículo en distintas traducciones, sigas una profecía
a través de ambos testamentos o leas un libro como prosa continua tal como lo
hacía su público más antiguo, el lector está construido para apartarse de tu
camino.

## Cómo funciona la lectura

El lector se organiza en torno a **columnas**. Cada columna muestra un texto — una
traducción de la Biblia, el Corán, un comentario o tus propias notas. Puedes abrir
tantas columnas como quieras y mezclar tradiciones libremente. Las columnas se
desplazan juntas de forma predeterminada para que el mismo pasaje permanezca
alineado, y cualquier columna puede desvincularse para recorrerla de forma
independiente.

### Modos de visualización

Una idea central de la plataforma es que las divisiones que damos por sentadas —
capítulos, versículos, encabezados de sección — son capas editoriales añadidas
mucho después de que se escribieran los textos. Cada columna puede alternar entre
modos que revelan u ocultan estas capas:

- **Scriptio Continua** — texto continuo al estilo de los manuscritos antiguos, sin
  divisiones de capítulo ni de versículo. Los capítulos de un libro fluyen juntos;
  una línea etiquetada marca el límite de cada libro.
- **Capítulos (1227)** — el sistema de capítulos que Stephen Langton añadió hacia
  1227.
- **Versículos (1551)** — los números de versículo que Robert Estienne añadió en
  1551.
- **Títulos** — los encabezados de sección editoriales modernos, cuando la
  traducción los proporciona.

### Orden de lectura

Cada columna también puede presentar sus libros en distintos órdenes: el orden
**canónico** tradicional, o un orden **cronológico** académico que entrelaza
pasajes de unos libros con otros — de modo que los profetas y los salmos puedan
leerse en su marco histórico y no de forma aislada.

### Leer el Corán

Una columna del Corán muestra el texto árabe, y puedes elegir una traducción para
mostrarla debajo de cada aleya (aya). El árabe sigue siendo lo primario, con la
traducción emparejada debajo en la misma columna desplazable, de modo que ambos
nunca se separen. El selector de traducción ofrece únicamente las traducciones de
ese Corán.

### Enlaces para compartir

El estado completo del lector — el texto de cada columna, el pasaje en el que está
abierta, su modo de visualización y orden de lectura, cualquier superposición de
traducción y si las columnas se desplazan juntas — queda capturado en la URL de la
página. Copia el enlace para volver exactamente a la misma vista más tarde, o
compártelo con otra persona. Las referencias son independientes de la edición y
del idioma, de modo que un enlace creado en una traducción se abre correctamente
en otra.

## Textos disponibles actualmente

<!-- AUTOGEN:translations -->
_BaseX query skipped (install `requests` to enable live ingestion stats)._
<!-- /AUTOGEN:translations -->

Se añaden continuamente más traducciones y tradiciones — incluidos el Corán, las
colecciones de hadices y versiones adicionales de la Biblia en muchos idiomas.

## Audio por capítulos

Las ediciones de dominio público van recibiendo audio hablado: un MP3 por capítulo,
más un índice de desplazamientos en milisegundos por versículo, para que la línea
de lectura pueda seguir el texto y un versículo concreto siga siendo enlazable.
Cobertura actual:

<!-- AUTOGEN:audio -->
_No audio manifest at `audio/index.json`; coverage table not refreshed. Set `COMMONROOT_AUDIO_INDEX` to point at it._
<!-- /AUTOGEN:audio -->

Es un esfuerzo de buena fe para que las Escrituras de dominio público existan en
audio, no una biblia sonora editada. La lectura es sintética — síntesis de voz
neuronal, una voz por edición, sin narrador humano ni paso por estudio — y se
genera unos pocos capítulos cada noche dentro de una cuota gratuita de caracteres,
así que una edición del tamaño de una biblia tarda meses en completarse. Solo se
leen textos de dominio público; las ediciones cuya licencia prohíbe las obras
derivadas quedan excluidas a propósito.

La idea es cubrir los textos que no tienen nada. El finés de 1933 y el Nuevo
Testamento hebreo de Delitzsch carecen de una edición en audio digna de ese
nombre, mientras que el inglés está abundantemente servido en otros sitios: por eso
el inglés va deliberadamente el último de la cola, y no el primero.

### En qué se equivoca la lectura automática

Conviene saberlo antes de escuchar, y merece la pena avisar cuando se oye:

- **Nombres propios.** Los nombres hebreos y griegos se pronuncian mal con
  frecuencia. El texto del versículo llega al motor como texto corrido, sin
  diccionario de pronunciación, de modo que nada corrige un nombre mal adivinado.
- **Ortografía arcaica.** El finés de 1776 es anterior a la ortografía moderna y el
  normalizador de texto del motor no se construyó para ella. Números, abreviaturas
  y grafías antiguas se leen como el motor las lea.
- **Una sola voz para todo.** Narración, diálogo y cita comparten el mismo
  registro; el audio nunca marca quién habla.
- **Las marcas de versículo pueden ser imprecisas.** Los capítulos se sintetizan
  como prosa continua a propósito, para que el fraseo cruce las divisiones de
  versículo en lugar de detenerse en cada una. Los desplazamientos provienen de
  marcas que el motor comunica al pasar por cada versículo y suelen ser exactos,
  pero un capítulo en el que comunica menos marcas que versículos tiene se publica
  igualmente y el faltante solo queda anotado en el registro. Algunos capítulos,
  por tanto, pueden seguirse de forma imprecisa.
- **Costuras en capítulos largos.** Todo lo que pasa de unos 7.500 caracteres se
  sintetiza por partes y se vuelve a unir. Las uniones se colocan en límites de
  versículo, donde una pausa es natural, pero alguna vez se oyen.

Nada de lo ya generado se regenera automáticamente — un capítulo en disco es un
capítulo ya pagado —, así que corregir una mala lectura es un acto deliberado:
borrar ese capítulo y dejar que la ejecución de la noche siguiente lo rehaga.

## Comentarios y argumentos

Más allá de los textos primarios, la plataforma recopila e indexa material
argumentativo sobre versículos concretos, extraído de una variedad de
perspectivas dentro de las tradiciones abrahámicas y del análisis crítico. Está
vinculado a los versículos que trata, de modo que un lector que estudie un pasaje
controvertido pueda ver cómo se argumenta desde distintos ángulos.

Las fuentes están organizadas por canal y perspectiva:

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

## Apoyo

Common Root? es gratuito y siempre lo será. Si te resulta útil, tu apoyo
ayuda a cubrir el alojamiento, el acceso a las traducciones con licencia y el
tiempo para añadir nuevos textos y funciones.

Buy Me a Coffee: https://buymeacoffee.com/christaclaw
