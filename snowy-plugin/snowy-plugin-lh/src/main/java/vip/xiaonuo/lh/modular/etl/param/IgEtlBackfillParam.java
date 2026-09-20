package vip.xiaonuo.lh.modular.etl.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 补数：按分区/水位触发一次 DS 实例
 */
@Getter
@Setter
public class IgEtlBackfillParam {

    @NotBlank(message = "id不能为空")
    private String id;

    /** 水位键，如 dt / partition */
    @NotBlank(message = "markKey不能为空")
    private String markKey;

    /** 水位值，如 2026-09-18 */
    @NotBlank(message = "markValue不能为空")
    private String markValue;

    private String env;
}
