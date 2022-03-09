/**
 * @license
 * Copyright (C) 2012 Jeffrey B. Arnold
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
PR.registerLangHandler(PR.createSimpleLexer([["pln",/^[\t\n\r \xa0]+/,null,"\t\n\r \u00a0"],["str",/^"(?:[^"\\]|\\[\S\s])*(?:"|$)/,null,'"'],["str",/^'(?:[^'\\]|\\[\S\s])*(?:'|$)/,null,"'"]],[["com",/^#.*/],["kwd",/^(?:if|else|for|while|repeat|in|next|break|return|switch|function)(?![\w.])/],["lit",/^0[Xx][\dA-Fa-f]+([Pp]\d+)?[Li]?/],["lit",/^[+-]?(\d+(\.\d+)?|\.\d+)([Ee][+-]?\d+)?[Li]?/],["lit",/^(?:NULL|NA(?:_(?:integer|real|complex|character)_)?|Inf|TRUE|FALSE|NaN|\.\.(?:\.|\d+))(?![\w.])/],
["pun",/^(?:<<?-|->>?|-|==|<=|>=|<|>|&&?|!=|\|\|?|[!*+/^]|%.*?%|[$=@~]|:{1,3}|[(),;?[\]{}])/],["pln",/^(?:[A-Za-z]+[\w.]*|\.[^\W\d][\w.]*)(?![\w.])/],["str",/^`.+`/]]),["r","s","R","S","Splus"]);
