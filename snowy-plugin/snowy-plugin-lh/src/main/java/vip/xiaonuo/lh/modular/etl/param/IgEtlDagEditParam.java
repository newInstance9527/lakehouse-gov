package vip.xiaonuo.lh.modular.etl.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IgEtlDagEditParam {
    @NotBlank(message = "id不能为空")
    private String id;
    private String name;
    private String description;
    private String cron;
    private String owner;
    private String defaultEngine;
    private String sla;
    private String env;
    private String status;
}
