package vip.xiaonuo.lh.modular.etl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlEdge;

public interface IgEtlEdgeMapper extends BaseMapper<IgEtlEdge> {

    /**
     * 物理清空某 DAG 全部边（全量替换用，避免软删后 uq_ig_etl_edge 冲突）
     */
    @Delete("DELETE FROM ig_etl_edge WHERE dag_id = #{dagId}")
    int physicalDeleteByDagId(@Param("dagId") String dagId);
}
