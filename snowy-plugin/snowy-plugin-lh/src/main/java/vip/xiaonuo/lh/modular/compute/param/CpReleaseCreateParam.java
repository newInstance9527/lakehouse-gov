package vip.xiaonuo.lh.modular.compute.param;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CpReleaseCreateParam {
    private String scriptId;
    private String ws;
    private String engine;
    private String env;
    /** 幂等键；亦可放请求头 Idempotency-Key / X-Idempotency-Key */
    private String idempotencyKey;
}
