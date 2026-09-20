package vip.xiaonuo.lh.modular.etl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

public interface IgEtlNodeMapper extends BaseMapper<IgEtlNode> {

    /**
     * 物理删除节点（图保存移除/复活同 key 时，避免软删占 uq_ig_etl_node）
     */
    @Delete("DELETE FROM ig_etl_node WHERE id = #{id}")
    int physicalDeleteById(@Param("id") String id);

    @Delete("DELETE FROM ig_etl_node WHERE dag_id = #{dagId} AND node_key = #{nodeKey}")
    int physicalDeleteByDagAndKey(@Param("dagId") String dagId, @Param("nodeKey") String nodeKey);
}
