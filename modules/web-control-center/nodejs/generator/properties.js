/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

var _ = require('lodash');

var generatorUtils = require("./common");

/**
 * Generate properties file with properties stubs for stores data sources.
 *
 * @param cluster Configuration to process.
 * @returns {string} Generated content.
 */
exports.generateProperties = function(cluster) {
    var res = generatorUtils.builder();

    res.line('# ' + generatorUtils.mainComment());
    res.line();

    // Generate data sources properties.
    if (cluster.caches && cluster.caches.length > 0) {
        var datasources = [];

        _.foreach(cluster.caches, function (cache) {
            if (cache.cacheStoreFactory && cache.cacheStoreFactory.kind) {
                var storeFactory = cache.cacheStoreFactory[cache.cacheStoreFactory.kind];

                if (storeFactory.dialect) {
                    var beanId = storeFactory.dataSourceBean;

                    if (!_.contains(datasources, beanId)) {
                        datasources.push(beanId);

                        res.line(beanId + '.jdbc.url=YOUR_JDBC_URL');
                        res.line(beanId + '.jdbc.username=YOUR_USER_NAME');
                        res.line(beanId + '.jdbc.password=YOUR_PASSWORD');
                        res.line();
                    }
                }
            }
        });
    }

    return res.join();
};
