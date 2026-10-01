-- Panel 1: horas por usuario y día (barras)
SELECT day AS time, user_label, SUM(hours) AS hours
FROM v_daily_app
WHERE $__timeFilter(day)
GROUP BY day, user_label
ORDER BY day;

-- Panel 2: top 10 apps por usuario (tabla)
SELECT user_label, app, ROUND(SUM(hours)::numeric, 2) AS hours
FROM v_daily_app
WHERE $__timeFilter(day)
GROUP BY user_label, app
ORDER BY hours DESC
LIMIT 10;

-- Panel 3: activo vs AFK por día (barras apiladas)
SELECT day AS time, status, SUM(hours) AS hours
FROM v_daily_afk
WHERE $__timeFilter(day)
GROUP BY day, status
ORDER BY day;

-- Panel 4: dispositivos y última señal (tabla)
SELECT user_label, hostname, tz, last_seen_at,
       now() - last_seen_at AS silence
FROM device
ORDER BY last_seen_at DESC NULLS LAST;
