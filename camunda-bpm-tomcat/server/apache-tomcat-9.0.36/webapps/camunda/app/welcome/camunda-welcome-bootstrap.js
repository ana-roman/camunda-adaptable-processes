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

window.__define("camunda-welcome-bootstrap",["./scripts/camunda-welcome-ui"],function(){"use strict";var c=window.CamundaWelcomeUi;requirejs.config({baseUrl:"../../../lib"});var a=window;c.exposePackages(a),window.define=window.__define,window.require=window.__require,requirejs(["globalize"],function(e){e(requirejs,["angular","camunda-commons-ui","camunda-bpm-sdk-js","jquery","angular-data-depend","moment","events"],a);var o=window.PLUGIN_PACKAGES||[],i=window.PLUGIN_DEPENDENCIES||[];o.forEach(function(e){var o=document.createElement("link");o.setAttribute("rel","stylesheet"),o.setAttribute("href",e.location+"/plugin.css"),document.head.appendChild(o)}),requirejs.config({packages:o,baseUrl:"../",paths:{ngDefine:"../../lib/ngDefine"}});var n=["angular","ngDefine"].concat(i.map(function(e){return e.requirePackageName}));requirejs(n,function(e){var o,n;window.camWelcomeConf&&window.camWelcomeConf.csrfCookieName&&e.module("cam.commons").config(["$httpProvider",function(e){e.defaults.xsrfCookieName=window.camWelcomeConf.csrfCookieName}]),void 0!==window.camWelcomeConf&&window.camWelcomeConf.customScripts?(o=window.camWelcomeConf.customScripts||{},n={},["baseUrl","paths","bundles","shim","map","config","packages","waitSeconds","context","callback","enforceDefine","xhtml","urlArgs","scriptType"].forEach(function(e){o[e]&&(n[e]=o[e])}),requirejs.config(n),requirejs(o.deps||[],function(){e.module("cam.welcome.custom",o.ngDeps),window.define=void 0,window.require=void 0,c(i)})):(e.module("cam.welcome.custom",[]),require([],function(){window.define=void 0,window.require=void 0,c(i)}))})})}),requirejs(["camunda-welcome-bootstrap"],function(){});