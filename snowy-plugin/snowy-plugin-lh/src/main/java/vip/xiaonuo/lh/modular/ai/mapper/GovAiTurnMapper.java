package vip.xiaonuo.lh.modular.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.xiaonuo.lh.modular.ai.entity.GovAiTurn;

import java.util.Date;

public interface GovAiTurnMapper extends BaseMapper<GovAiTurn> {

    /**
     * 工作空间内 assistant 轮次平均响应（毫秒）；无样本返回 null。
     */
    @Select("""
            SELECT AVG(t.latency_ms) FROM gov_ai_turn t
            INNER JOIN gov_ai_session s ON s.id = t.session_id
            WHERE t.role = 'assistant'
              AND t.latency_ms IS NOT NULL AND t.latency_ms > 0
              AND t.create_time >= #{from}
              AND (s.ws = #{ws} OR s.ws = '*')
            """)
    Double avgLatencyMsSince(@Param("ws") String ws, @Param("from") Date from);
}
