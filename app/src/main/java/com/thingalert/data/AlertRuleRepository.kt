package com.thingalert.data

import kotlinx.coroutines.flow.Flow

class AlertRuleRepository(
  private val alertRuleDao: AlertRuleDao
) {
  fun observeRules(): Flow<List<AlertRuleEntity>> = alertRuleDao.observeRules()

  fun observeEnabledRules(): Flow<List<AlertRuleEntity>> = alertRuleDao.observeEnabledRules()

  suspend fun addRule(rule: AlertRuleEntity): Long = alertRuleDao.insert(rule)

  suspend fun setEnabled(id: Long, enabled: Boolean) {
    alertRuleDao.setEnabled(id, enabled)
  }

  suspend fun deleteRule(id: Long) {
    alertRuleDao.deleteById(id)
  }
}
