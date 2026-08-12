-- D-149: sp09-boardgame TiDB DDL (Xiangqi board game education + online play)

-- Game rooms (binds course/lesson for Match-as-Lesson)
CREATE TABLE IF NOT EXISTS bg_game_room (
    room_id         BIGINT       NOT NULL,
    tenant_id       BIGINT       NOT NULL,
    course_id       BIGINT       NULL,
    lesson_id       BIGINT       NULL,
    room_name       VARCHAR(200) NOT NULL,
    red_player_id   BIGINT       NULL,
    black_player_id BIGINT       NULL,
    max_players     INT          NOT NULL DEFAULT 2,
    status          INT          NOT NULL DEFAULT 0 COMMENT '0=WAITING,1=PLAYING,2=FINISHED',
    match_id        BIGINT       NULL,
    created_at      BIGINT       NOT NULL,
    updated_at      BIGINT       NOT NULL,
    lock_version    BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (room_id),
    INDEX idx_tenant_status (tenant_id, status),
    INDEX idx_course (course_id),
    INDEX idx_room_match (match_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Game rooms';

-- Game matches (ongoing/completed Xiangqi matches)
CREATE TABLE IF NOT EXISTS bg_game_match (
    match_id        BIGINT       NOT NULL,
    room_id         BIGINT       NOT NULL,
    tenant_id       BIGINT       NOT NULL,
    course_id       BIGINT       NULL,
    red_player_id   BIGINT       NOT NULL,
    black_player_id BIGINT       NOT NULL,
    fen_state       TEXT         NOT NULL COMMENT 'Current FEN board state',
    move_history    TEXT         NOT NULL DEFAULT '[]' COMMENT 'JSON array of moves',
    result          INT          NOT NULL DEFAULT 0 COMMENT '0=ONGOING,1=RED_WIN,2=BLACK_WIN,3=DRAW',
    move_count      INT          NOT NULL DEFAULT 0,
    current_side    INT          NOT NULL DEFAULT 0 COMMENT '0=RED,1=BLACK',
    created_at      BIGINT       NOT NULL,
    finished_at     BIGINT       NULL,
    lock_version    BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (match_id),
    INDEX idx_match_room (room_id),
    INDEX idx_match_tenant (tenant_id),
    INDEX idx_match_red (red_player_id),
    INDEX idx_match_black (black_player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Game matches';

-- Game replays (learning materials — review completed games)
CREATE TABLE IF NOT EXISTS bg_game_replay (
    replay_id       BIGINT       NOT NULL,
    match_id        BIGINT       NOT NULL,
    tenant_id       BIGINT       NOT NULL,
    room_id         BIGINT       NOT NULL,
    course_id       BIGINT       NULL,
    lesson_id       BIGINT       NULL,
    red_player_id   BIGINT       NOT NULL,
    black_player_id BIGINT       NOT NULL,
    result          INT          NOT NULL COMMENT '1=RED_WIN,2=BLACK_WIN,3=DRAW',
    move_count      INT          NOT NULL,
    move_history    TEXT         NOT NULL COMMENT 'Full JSON move history',
    initial_fen     TEXT         NULL,
    created_at      BIGINT       NOT NULL,
    lock_version    BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (replay_id),
    INDEX idx_replay_match (match_id),
    INDEX idx_replay_tenant (tenant_id),
    INDEX idx_replay_course (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Game replays';
