/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

window.__define("camunda-admin-bootstrap",["./scripts/camunda-admin-ui"],function(){"use strict";var a=window.CamundaAdminUi;requirejs.config({baseUrl:"../../../lib"});var r=window;a.exposePackages(r),window.define=window.__define,window.require=window.__require,requirejs(["globalize"],function(n){n(requirejs,["angular","camunda-commons-ui","camunda-bpm-sdk-js","jquery"],r);var i=window.PLUGIN_PACKAGES||[],o=window.PLUGIN_DEPENDENCIES||[];i.forEach(function(n){var i=document.createElement("link");i.setAttribute("rel","stylesheet"),i.setAttribute("href",n.location+"/plugin.css"),document.head.appendChild(i)}),requirejs.config({packages:i,baseUrl:"../",paths:{ngDefine:"../../lib/ngDefine"}});var e=["angular","ngDefine"].concat(o.map(function(n){return n.requirePackageName}));requirejs(e,function(n){var i,e;window.camAdminConf&&window.camAdminConf.csrfCookieName&&n.module("cam.commons").config(["$httpProvider",function(n){n.defaults.xsrfCookieName=window.camAdminConf.csrfCookieName}]),void 0!==window.camAdminConf&&window.camAdminConf.customScripts?(i=window.camAdminConf.customScripts||{},e={},["baseUrl","paths","bundles","shim","map","config","packages","waitSeconds","context","callback","enforceDefine","xhtml","urlArgs","scriptType"].forEach(function(n){i[n]&&(e[n]=i[n])}),requirejs.config(e),requirejs(i.deps||[],function(){n.module("cam.admin.custom",i.ngDeps),window.define=void 0,window.require=void 0,a(o)})):(n.module("cam.admin.custom",[]),require([],function(){window.define=void 0,window.require=void 0,a(o)}))})})}),requirejs(["camunda-admin-bootstrap"],function(){});