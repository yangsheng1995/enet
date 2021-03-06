package cn.xnatural.enet.test.dao.repo;

import cn.xnatural.enet.server.dao.hibernate.BaseRepo;
import cn.xnatural.enet.server.dao.hibernate.Repo;
import cn.xnatural.enet.test.dao.entity.UploadFile;

import java.util.Date;

@Repo
public class UploadFileRepo extends BaseRepo<UploadFile, Long> {

    @Override
    public <S extends UploadFile> S saveOrUpdate(S e) {
        Date d = new Date();
        if (e.getCreateTime() == null) e.setCreateTime(d);
        if (e.getUpdateTime() == null) e.setCreateTime(d);
        return super.saveOrUpdate(e);
    }
}
