-- 未活動通知已撤；舊列若寫過 Google email，部署時清掉。
UPDATE users SET email = NULL WHERE email IS NOT NULL;
