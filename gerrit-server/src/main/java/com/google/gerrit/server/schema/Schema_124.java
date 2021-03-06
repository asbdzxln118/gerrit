// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.schema;

import com.google.common.base.Function;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.AccountSshKey;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.account.VersionedAuthorizedKeys;
import com.google.gerrit.server.account.VersionedAuthorizedKeys.SimpleSshKeyCreator;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MetaDataUpdate;
import com.google.gwtorm.jdbc.JdbcSchema;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class Schema_124 extends SchemaVersion {
  private final GitRepositoryManager repoManager;
  private final AllUsersName allUsersName;
  private final PersonIdent serverUser;

  @Inject
  Schema_124(Provider<Schema_123> prior,
      GitRepositoryManager repoManager,
      AllUsersName allUsersName,
      @GerritPersonIdent PersonIdent serverUser) {
    super(prior);
    this.repoManager = repoManager;
    this.allUsersName = allUsersName;
    this.serverUser = serverUser;
  }

  @Override
  protected void migrateData(ReviewDb db, UpdateUI ui)
      throws OrmException, SQLException {
    Multimap<Account.Id, AccountSshKey> imports = ArrayListMultimap.create();
    try (Statement stmt = ((JdbcSchema) db).getConnection().createStatement();
      ResultSet rs = stmt.executeQuery(
          "SELECT "
          + "account_id, "
          + "seq, "
          + "ssh_public_key, "
          + "valid "
          + "FROM account_ssh_keys")) {
      while (rs.next()) {
        Account.Id accountId = new Account.Id(rs.getInt(1));
        int seq = rs.getInt(2);
        String sshPublicKey = rs.getString(3);
        AccountSshKey key = new AccountSshKey(
            new AccountSshKey.Id(accountId, seq), sshPublicKey);
        boolean valid = rs.getBoolean(4);
        if (!valid) {
          key.setInvalid();
        }
        imports.put(accountId, key);
      }
    }

    if (imports.isEmpty()) {
      return;
    }

    try (Repository git = repoManager.openRepository(allUsersName);
        RevWalk rw = new RevWalk(git)) {
      BatchRefUpdate bru = git.getRefDatabase().newBatchUpdate();

      for (Map.Entry<Account.Id, Collection<AccountSshKey>> e : imports.asMap()
          .entrySet()) {
        try (MetaDataUpdate md = new MetaDataUpdate(
                 GitReferenceUpdated.DISABLED, allUsersName, git, bru);
             VersionedAuthorizedKeys authorizedKeys =
                 new VersionedAuthorizedKeys(
                     new SimpleSshKeyCreator(), e.getKey())) {
          md.getCommitBuilder().setAuthor(serverUser);
          md.getCommitBuilder().setCommitter(serverUser);

          authorizedKeys.load(md);
          authorizedKeys.setKeys(fixInvalidSequenceNumbers(e.getValue()));
          authorizedKeys.commit(md);
        }
      }

      bru.execute(rw, NullProgressMonitor.INSTANCE);
    } catch (ConfigInvalidException | IOException ex) {
      throw new OrmException(ex);
    }
  }

  private Collection<AccountSshKey> fixInvalidSequenceNumbers(
      Collection<AccountSshKey> keys) {
    Ordering<AccountSshKey> o =
        Ordering.natural().onResultOf(new Function<AccountSshKey, Integer>() {
          @Override
          public Integer apply(AccountSshKey sshKey) {
            return sshKey.getKey().get();
          }
        });
    List<AccountSshKey> fixedKeys = new ArrayList<>(keys);
    AccountSshKey minKey = o.min(keys);
    while (minKey.getKey().get() <= 0) {
      AccountSshKey fixedKey = new AccountSshKey(
          new AccountSshKey.Id(minKey.getKey().getParentKey(),
              Math.max(o.max(keys).getKey().get() + 1, 1)),
          minKey.getSshPublicKey());
      Collections.replaceAll(fixedKeys, minKey, fixedKey);
      minKey = o.min(fixedKeys);
    }
    return fixedKeys;
  }
}
