-- The reader's typeset "book" look (drop caps, a serif face, an accent colour),
-- matching the print designer's preview. Defaults are NOT stored: NULL means "the
-- default for a signed-in reader" — the book look, rubric red, traditional titles
-- on — so a user who never opens Preferences gets the default, and the default
-- can move without a data migration. A signed-out reader never reaches these
-- columns at all and always gets the classic reader.
ALTER TABLE user_preferences
    ADD COLUMN reader_style       VARCHAR(10) NULL,    -- 'book' | 'classic'
    ADD COLUMN reader_accent      VARCHAR(10) NULL,    -- 'rubric' | 'black' | 'indigo' | 'sepia' | 'forest'
    ADD COLUMN reader_long_titles BOOLEAN     NULL;    -- the traditional title under each book's name
