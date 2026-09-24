-- AI 模型能力分类：扩展 kind（chat/embed/image）+ 视觉输入 / 图片输出能力位
-- 对话模型可单独标记 supports_vision（多模态识图），与 kind=image（生图）互不混淆

ALTER TABLE `gov_ai_model`
  MODIFY COLUMN `kind` varchar(16) NOT NULL DEFAULT 'chat'
    COMMENT 'chat=对话 / embed=向量 / image=图片生成',
  ADD COLUMN `supports_vision` tinyint NOT NULL DEFAULT 0
    COMMENT '对话模型是否支持图片输入（视觉/多模态）' AFTER `kind`,
  ADD COLUMN `supports_image_output` tinyint NOT NULL DEFAULT 0
    COMMENT '是否支持图片输出（生图；image 类默认建议为 1）' AFTER `supports_vision`;

-- 存量 image 类暂无；对话/向量默认不支持视觉与生图输出
UPDATE `gov_ai_model`
SET `supports_vision` = 0,
    `supports_image_output` = CASE WHEN `kind` = 'image' THEN 1 ELSE 0 END
WHERE `delete_flag` = 'NOT_DELETE' OR `delete_flag` IS NULL;
