-- Kairo: English becomes the default language of the `language` setting (it used to be Spanish).
-- Every account that exists at this point has effectively been using Spanish (the former default), so pin it
-- explicitly: nobody flips from Spanish to English because the default changed. Accounts created from now on
-- get their language from the request (Accept-Language) or KAIRO_DEFAULT_LANGUAGE, and store it at creation.
insert into user_settings (user_id, data)
select id, jsonb_build_object('language', 'es') from users
on conflict (user_id) do update
    set data = user_settings.data || jsonb_build_object('language', 'es')
    where user_settings.data -> 'language' is null;
