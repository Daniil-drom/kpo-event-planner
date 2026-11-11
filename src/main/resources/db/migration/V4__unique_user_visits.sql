-- One saved entry per user and event. Status and time are edited in that entry.
ALTER TABLE user_visits ADD CONSTRAINT user_visits_user_event_key UNIQUE (user_id, event_id);
