package com.aiproxyoauth.model;

import com.aiproxyoauth.provider.ProviderModel;

import java.util.List;

public interface ModelCatalog {

    List<ProviderModel> resolveModels() throws Exception;
}
