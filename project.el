(require 'org-publish)
(setq base-dir
      (file-name-as-directory
       (file-name-directory
	(buffer-file-name))))

(setq
 org-publish-project-alist
 (list
  (list
   "pallet"
   :base-directory base-dir
   :publishing-directory (expand-file-name (concat base-dir "../autodoc/"))
   :base-extension "org"
   :recursive t
   :publishing-function 'org-publish-org-to-html
   :auto-sitemap t
   :sitemap-filename "sitemap.org"
   :sitemap-title "Sitemap")
  (list "static"
   :base-directory base-dir
   :base-extension "css\\|js\\|png\\|jpeg\\|gif\\|pdf\\|mp3"
   :publishing-directory (expand-file-name (concat base-dir "../autodoc/"))
   :recursive t
   :publishing-function 'org-publish-attachment
   )
  '("all" :components ("pallet" "static"))))
