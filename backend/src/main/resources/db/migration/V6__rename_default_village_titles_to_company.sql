UPDATE mini_homes
SET title = regexp_replace(title, '의 AI 마을$', '의 AI 회사'),
    updated_at = CURRENT_TIMESTAMP
WHERE title LIKE '%의 AI 마을';
