-- Crea un dispositivo y guarda solo el hash del token.
-- Uso (local):  psql -h localhost -U aw -d aw -v label=omar -v host=LAPTOP-OMAR -v token="$(openssl rand -hex 32)" -f create_device.sql
-- Uso (VPS):    docker compose exec -T db psql -U aw -d aw -v label=omar -v host=LAPTOP-OMAR -v token=XXXX -f - < create_device.sql
-- Guarda el token que pasaste en -v token: no se puede recuperar desde la base.
INSERT INTO device (id, user_label, hostname, token_hash)
VALUES (gen_random_uuid(), :'label', :'host', encode(digest(:'token', 'sha256'), 'hex'))
RETURNING id, user_label, hostname;
