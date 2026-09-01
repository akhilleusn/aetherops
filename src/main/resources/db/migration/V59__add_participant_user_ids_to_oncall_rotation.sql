-- Optional parallel mapping from oncall_rotation_participants (phone numbers) to a User id,
-- so paging can consult PTO/calendar availability (Phase 7) before choosing who to page.
-- Nullable/sparse by design: existing rotations have no rows here, and OnCallService treats
-- a missing mapping the same as "no PTO info" — always available, never blocks paging.
CREATE TABLE oncall_rotation_participant_user_ids (
    oncall_rotation_id  BIGINT      NOT NULL REFERENCES oncall_rotation(id) ON DELETE CASCADE,
    participant_order   INTEGER     NOT NULL,
    participant_user_id UUID        NOT NULL,
    PRIMARY KEY (oncall_rotation_id, participant_order)
);
