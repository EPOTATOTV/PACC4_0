-- =====================================================================
-- V10: v4.7 客服工单系统
-- 说明：v4.1 曾用 t_support_ticket 做轻量多渠道工单（subject/body/channel）；
--      v4.7 重建为含分类/优先级/SLA/首响跟踪的工单表，并新增 FAQ 知识库表。
--      由于 Hibernate 采用 ddl-auto=validate，此处 DROP 旧表后按新 schema 重建。
-- =====================================================================

DROP TABLE IF EXISTS t_support_ticket;

CREATE TABLE t_support_ticket (
    id              VARCHAR(255) NOT NULL,
    pteid           VARCHAR(255),
    category        VARCHAR(255),
    title           VARCHAR(255),
    description     LONGTEXT,
    status          VARCHAR(255) NOT NULL DEFAULT 'OPEN',
    priority        VARCHAR(255) NOT NULL DEFAULT 'P3',
    assignee        VARCHAR(255),
    first_reply_at  DATETIME(6),
    resolved_at     DATETIME(6),
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_ticket_category (category),
    KEY idx_ticket_status (status),
    KEY idx_ticket_priority (priority),
    KEY idx_ticket_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

DROP TABLE IF EXISTS t_support_faq;

CREATE TABLE t_support_faq (
    id          VARCHAR(255) NOT NULL,
    question    VARCHAR(255),
    answer      LONGTEXT,
    keywords    VARCHAR(255),
    created_at  DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_faq_keywords (keywords)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;