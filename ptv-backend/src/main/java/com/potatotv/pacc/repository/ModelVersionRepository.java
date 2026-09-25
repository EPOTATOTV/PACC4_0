package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ModelVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelVersionRepository extends JpaRepository<ModelVersion, String> {

    /** 某模型类型的全部版本（按登记时间倒序，最新的在前）。 */
    List<ModelVersion> findByModelTypeOrderByCreatedAtDesc(String modelType);

    /** 全部模型的版本（按登记时间倒序，未指定类型时使用）。 */
    List<ModelVersion> findAllByOrderByCreatedAtDesc();

    /** 某模型类型下指定状态的最新一行（取 active / 取 rollback 均走本查询）。 */
    Optional<ModelVersion> findFirstByModelTypeAndStatusOrderByCreatedAtDesc(String modelType, String status);

    /** 某模型类型下指定状态、按发布时间倒序的最新一行（回退时挑「上一个 active」）。 */
    Optional<ModelVersion> findFirstByModelTypeAndStatusOrderByPublishedAtDesc(String modelType, String status);

    /**
     * 某模型类型最近登记的一行：版本号由流水线单调递增生成，
     * 故「最近登记」即当前最大版本号，用于推导下一个版本号。
     */
    Optional<ModelVersion> findTopByModelTypeOrderByCreatedAtDesc(String modelType);

    /** 按模型字节摘要查已有版本：用于幂等去重（相同字节不重复登记）。 */
    Optional<ModelVersion> findFirstBySha256OrderByCreatedAtDesc(String sha256);
}