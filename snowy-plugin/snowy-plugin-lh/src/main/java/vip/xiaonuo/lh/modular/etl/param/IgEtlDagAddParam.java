package vip.xiaonuo.lh.modular.etl.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IgEtlDagAddParam {
    private String ws;
    @NotBlank(message = "dagCode不能为空")
    private String dagCode;
    @NotBlank(message = "name不能为空")
    private String name;
    private String description;
    private String cron;
    private String owner;
    private String defaultEngine;
    private String sla;
    private String env;
}
