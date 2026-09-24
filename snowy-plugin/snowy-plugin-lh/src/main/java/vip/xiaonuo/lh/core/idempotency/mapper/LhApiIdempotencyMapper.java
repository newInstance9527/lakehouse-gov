package vip.xiaonuo.lh.core.idempotency.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.xiaonuo.lh.core.idempotency.entity.LhApiIdempotency;

@Mapper
public interface LhApiIdempotencyMapper extends BaseMapper<LhApiIdempotency> {
}
