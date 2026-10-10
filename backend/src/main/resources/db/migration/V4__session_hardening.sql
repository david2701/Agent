-- Sesiones: recordar el refresh token anterior de cada dispositivo para detectar si alguien lo
-- reutiliza después de la rotación (señal de robo: se cierra la sesión de ese dispositivo).
alter table devices add column prev_refresh_token_hash text;
alter table devices add column refreshed_at timestamptz;
create index devices_prev_refresh_idx on devices(prev_refresh_token_hash) where prev_refresh_token_hash is not null;
