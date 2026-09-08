-- Umami analytics database + user. Runs ONLY on first-ever boot of a fresh
-- MySQL volume (docker-entrypoint-initdb.d semantics) — i.e. local dev resets.
-- On PROD the volume already exists, so this must be run manually once:
--   docker exec -it religioustext-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD"
--   ... then paste the statements below with the real ${UMAMI_DB_PASSWORD}.
-- Umami creates/migrates its own tables inside this database on first start.
CREATE DATABASE IF NOT EXISTS umami CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'umami'@'%' IDENTIFIED BY 'umamipassword';
GRANT ALL PRIVILEGES ON umami.* TO 'umami'@'%';
FLUSH PRIVILEGES;
