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
   :base-extension "org")))
