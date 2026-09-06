package com.potatotv.pacc.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v4.7 客服 FAQ 知识库条目：用于智能回复关键词匹配，answer 为官方回答。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_support_faq")
public class FaqEntry {

    @Id
    private String id;

    private String question;

    @Lob
    private String answer;

    /** 以逗号分隔的关键词。 */
    private String keywords;

    private Instant createdAt;
}