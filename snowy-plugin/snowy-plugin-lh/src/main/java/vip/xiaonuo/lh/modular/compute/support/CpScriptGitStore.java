package vip.xiaonuo.lh.modular.compute.support;

import cn.hutool.core.util.StrUtil;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 每工作空间一个本地 Git 仓库（分支 stg）。脚本正文只存在这里。
 */
@Component
public class CpScriptGitStore {

    private final LhProperties lhProperties;

    public CpScriptGitStore(LhProperties lhProperties) {
        this.lhProperties = lhProperties;
    }

    public String read(String ws, String path) {
        ensure(ws);
        File file = new File(repoDir(ws), path);
        if (!file.isFile()) {
            return "";
        }
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CommonException("读取脚本失败: {}", e.getMessage());
        }
    }

    public String readAt(String ws, String path, String rev) {
        ensure(ws);
        try (Git git = Git.open(repoDir(ws))) {
            Repository repository = git.getRepository();
            ObjectId id = repository.resolve(rev);
            if (id == null) {
                throw new CommonException("找不到 Git 版本: {}", rev);
            }
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(id);
                try (TreeWalk tw = TreeWalk.forPath(repository, path, commit.getTree())) {
                    if (tw == null) {
                        throw new CommonException("版本 {} 中没有 {}", rev, path);
                    }
                    ObjectLoader loader = repository.open(tw.getObjectId(0));
                    return new String(loader.getBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            throw new CommonException("读取历史脚本失败: {}", e.getMessage());
        }
    }

    public String commit(String ws, String path, String content, String message, String author, String email) {
        ensure(ws);
        File file = new File(repoDir(ws), path);
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            Files.writeString(file.toPath(), content == null ? "" : content, StandardCharsets.UTF_8);
            try (Git git = Git.open(repoDir(ws))) {
                git.add().addFilepattern(path).call();
                var status = git.status().addPath(path).call();
                boolean unchanged = status.getAdded().isEmpty()
                        && status.getChanged().isEmpty()
                        && status.getModified().isEmpty()
                        && status.getRemoved().isEmpty();
                if (unchanged) {
                    ObjectId head = git.getRepository().resolve("HEAD");
                    return head == null ? "" : head.getName();
                }
                PersonIdent ident = new PersonIdent(
                        StrUtil.blankToDefault(author, "lakehouse"),
                        StrUtil.blankToDefault(email, "lakehouse@local"));
                RevCommit commit = git.commit()
                        .setMessage(StrUtil.blankToDefault(message, "save " + path))
                        .setAuthor(ident)
                        .setCommitter(ident)
                        .call();
                return commit.getName();
            }
        } catch (Exception e) {
            throw new CommonException("提交脚本到 Git 失败: {}", e.getMessage());
        }
    }

    public String tag(String ws, String name, String message) {
        ensure(ws);
        String tag = name.replaceAll("[^A-Za-z0-9._\\-]", "-");
        try (Git git = Git.open(repoDir(ws))) {
            git.tag().setName(tag).setMessage(StrUtil.blankToDefault(message, tag)).call();
            return tag;
        } catch (Exception e) {
            throw new CommonException("打 Git tag 失败: {}", e.getMessage());
        }
    }

    public List<String> tagsNewestFirst(String ws) {
        ensure(ws);
        try (Git git = Git.open(repoDir(ws)); RevWalk walk = new RevWalk(git.getRepository())) {
            List<Tagged> tags = new ArrayList<>();
            for (var ref : git.tagList().call()) {
                String full = ref.getName();
                String name = full.startsWith("refs/tags/") ? full.substring("refs/tags/".length()) : full;
                ObjectId id = ref.getPeeledObjectId() != null ? ref.getPeeledObjectId() : ref.getObjectId();
                RevCommit commit = walk.parseCommit(id);
                tags.add(new Tagged(name, commit.getCommitTime()));
            }
            tags.sort(Comparator.comparingInt(Tagged::time).reversed());
            List<String> names = new ArrayList<>();
            for (Tagged t : tags) {
                names.add(t.name());
            }
            return names;
        } catch (Exception e) {
            throw new CommonException("列举 Git tag 失败: {}", e.getMessage());
        }
    }

    /** 当前 tag 的上一个；没有当前 tag 时取最新一个。 */
    public String previousTag(String ws, String current) {
        List<String> tags = tagsNewestFirst(ws);
        if (tags.isEmpty()) {
            return null;
        }
        if (StrUtil.isBlank(current)) {
            return tags.get(0);
        }
        int idx = tags.indexOf(current);
        if (idx >= 0 && idx + 1 < tags.size()) {
            return tags.get(idx + 1);
        }
        if (idx < 0) {
            return tags.get(0);
        }
        return null;
    }

    public String bindRemote(String ws, String remoteUrl) {
        return bindRemote(ws, remoteUrl, true);
    }

    /**
     * 绑定 origin 并推送 stg（及可选 tags）。失败返回警告文案，不抛业务异常。
     */
    public String bindRemote(String ws, String remoteUrl, boolean pushTags) {
        if (StrUtil.isBlank(remoteUrl)) {
            return null;
        }
        ensure(ws);
        try (Git git = Git.open(repoDir(ws))) {
            var config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", remoteUrl.trim());
            config.save();
            try {
                var push = git.push().setRemote("origin")
                        .setRefSpecs(new RefSpec("refs/heads/stg:refs/heads/stg"));
                if (pushTags) {
                    push.setPushTags();
                }
                push.call();
                return null;
            } catch (Exception pushFail) {
                return "远程已记录但推送失败: " + pushFail.getMessage();
            }
        } catch (Exception e) {
            return "绑定 Git 远程失败: " + e.getMessage();
        }
    }

    /** 仅推送已有 remote（publish/rollback 后补推 tag）。 */
    public String pushOrigin(String ws, boolean pushTags) {
        ensure(ws);
        try (Git git = Git.open(repoDir(ws))) {
            String url = git.getRepository().getConfig().getString("remote", "origin", "url");
            if (StrUtil.isBlank(url)) {
                return "未配置 origin，跳过推送";
            }
            var push = git.push().setRemote("origin")
                    .setRefSpecs(new RefSpec("refs/heads/stg:refs/heads/stg"));
            if (pushTags) {
                push.setPushTags();
            }
            push.call();
            return null;
        } catch (Exception e) {
            return "推送 origin 失败: " + e.getMessage();
        }
    }

    /**
     * 从当前 HEAD 建分支并推送到 origin（评审分支 review/{id}）。
     * @return 警告文案；成功 null
     */
    public String createAndPushBranch(String ws, String branch) {
        String name = sanitizeBranch(branch);
        if (StrUtil.isBlank(name)) {
            return "分支名为空";
        }
        ensure(ws);
        try (Git git = Git.open(repoDir(ws))) {
            boolean exists = git.getRepository().findRef("refs/heads/" + name) != null;
            if (!exists) {
                git.branchCreate().setName(name).call();
            }
            String url = git.getRepository().getConfig().getString("remote", "origin", "url");
            if (StrUtil.isBlank(url)) {
                return "未配置 origin，分支仅本地: " + name;
            }
            git.push().setRemote("origin")
                    .setRefSpecs(new RefSpec("refs/heads/" + name + ":refs/heads/" + name))
                    .setForce(false)
                    .call();
            return null;
        } catch (Exception e) {
            return "创建/推送分支 " + name + " 失败: " + e.getMessage();
        }
    }

    /**
     * 确保 base 分支（默认 prod）存在并已推送；不存在时从当前 HEAD 创建。
     * 首次发布时 PR 可能无 diff（head==base），合并仍完成门闩。
     */
    public String ensureBaseBranch(String ws, String baseBranch) {
        String name = sanitizeBranch(StrUtil.blankToDefault(baseBranch, "prod"));
        return createAndPushBranch(ws, name);
    }

    private static String sanitizeBranch(String branch) {
        return StrUtil.blankToDefault(branch, "")
                .trim()
                .replaceAll("[^A-Za-z0-9._\\-/]", "-");
    }

    private void ensure(String ws) {
        File dir = repoDir(ws);
        File gitDir = new File(dir, ".git");
        if (gitDir.isDirectory()) {
            return;
        }
        try {
            Files.createDirectories(dir.toPath());
            try (Git git = Git.init().setDirectory(dir).setInitialBranch("stg").call()) {
                File keep = new File(dir, "scripts/.gitkeep");
                Files.createDirectories(keep.getParentFile().toPath());
                Files.writeString(keep.toPath(), "", StandardCharsets.UTF_8);
                git.add().addFilepattern("scripts/.gitkeep").call();
                PersonIdent ident = new PersonIdent("lakehouse", "lakehouse@local");
                git.commit().setMessage("init stg").setAuthor(ident).setCommitter(ident).call();
            }
        } catch (Exception e) {
            throw new CommonException("初始化脚本仓库失败: {}", e.getMessage());
        }
    }

    private File repoDir(String ws) {
        String root = lhProperties.getCompute() == null ? "./data/lh-git" : lhProperties.getCompute().getGitRoot();
        String safe = StrUtil.blankToDefault(ws, "default").replaceAll("[^A-Za-z0-9._\\-]", "_");
        return new File(StrUtil.blankToDefault(root, "./data/lh-git"), safe);
    }

    private record Tagged(String name, int time) {
    }
}
