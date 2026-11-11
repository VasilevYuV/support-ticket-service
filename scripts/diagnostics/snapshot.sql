-- Снимок данных и схемы для сравнения до и после учебного отказа.
SELECT jsonb_build_object(
    'categories', (SELECT jsonb_agg(c ORDER BY id) FROM categories c),
    'tickets', (SELECT jsonb_agg(t ORDER BY id) FROM tickets t),
    'history', (SELECT jsonb_agg(h ORDER BY id) FROM ticket_status_history h),
    'migrations', (SELECT jsonb_agg(m ORDER BY installed_rank) FROM flyway_schema_history m),
    'categorySequence', (SELECT jsonb_build_object('lastValue',last_value,'isCalled',is_called) FROM categories_id_seq),
    'historySequence', (SELECT jsonb_build_object('lastValue',last_value,'isCalled',is_called) FROM ticket_status_history_id_seq),
    'columns', (SELECT jsonb_agg(jsonb_build_object('table', table_name, 'column', column_name,
        'type', data_type, 'nullable', is_nullable, 'default', column_default,
        'identity', identity_generation, 'length', character_maximum_length,
        'precision', datetime_precision) ORDER BY table_name, ordinal_position)
        FROM information_schema.columns WHERE table_schema = 'public'),
    'constraints', (SELECT jsonb_agg(jsonb_build_object('table', c.relname, 'name', con.conname,
        'definition', pg_get_constraintdef(con.oid)) ORDER BY c.relname, con.conname)
        FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'),
    'indexes', (SELECT jsonb_agg(jsonb_build_object('table', tablename, 'name', indexname,
        'definition', indexdef) ORDER BY tablename, indexname) FROM pg_indexes WHERE schemaname = 'public')
)::text;
