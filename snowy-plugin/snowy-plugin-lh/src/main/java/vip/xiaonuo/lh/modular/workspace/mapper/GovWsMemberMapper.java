package vip.xiaonuo.lh.modular.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import vip.xiaonuo.lh.modular.workspace.entity.GovWsMember;

public interface GovWsMemberMapper extends BaseMapper<GovWsMember> {

    /** 物理删除（替换成员时释放唯一键） */
    @Delete("DELETE FROM gov_ws_member WHERE ws_code = #{wsCode}")
    int physicalDeleteByWs(@Param("wsCode") String wsCode);

    @Delete("DELETE FROM gov_ws_member WHERE id = #{id}")
    int physicalDeleteById(@Param("id") String id);
}
