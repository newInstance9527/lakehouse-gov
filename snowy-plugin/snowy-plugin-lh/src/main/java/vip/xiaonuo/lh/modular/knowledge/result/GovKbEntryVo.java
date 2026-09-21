package vip.xiaonuo.lh.modular.knowledge.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Schema(description = "知识条目VO")
public class GovKbEntryVo {

    private String id;
    private String title;
    private String cat;
    private String body;
    private String desc;
    private String source;
    private String fileName;
    private String strategy;
    private Integer chunkSize;
    private Integer overlap;
    private String separator;
    private String embedModelId;
    private String refsJson;
    private Object refs;
    private Integer citeCnt;
    private Integer chunkCount;
    private String status;
    private String ws;
    private Integer revision;
    private Date updateTime;
    private List<Map<String, Object>> chunks;
}
